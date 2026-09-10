/*
 * Copyright 2025 Dmitry Barashev, JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package org.jetbrains.edu.dbi2026

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.edu.dbi2026.app.initializeFactories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.use

class TransactionManagerTest {
    private lateinit var storage: Storage
    @BeforeEach
    fun initialize() {
        storage = createHardDriveEmulatorStorage()
        initializeFactories(storage)
    }

    @Test
    fun `redo recovery policy smoke test`() {
        val walStorage = createHardDriveEmulatorStorage()
        val wal = FakeWAL()
        val recovery = FakeRecovery()
        doRecoveryPolicySmokeTest(walStorage, wal, recovery)
    }

    fun doRecoveryPolicySmokeTest(walStorage: Storage, wal: WAL, recovery: Recovery) {
        val scheduler = FakeScheduler()
        val txnManager = TransactionManager(scheduler, LogManager(storage, 20, wal))

        val certainlyCommitted = mutableListOf<Int>()
        (1..10).forEach { pageId ->
            txnManager.txn {cache, txnId ->
                delay(Random.nextLong(100))
                cache.get(pageId * 10).use {
                    it.putRecord(Record1(intField(pageId)).asBytes())
                }
                txnManager.commit(txnId)
                certainlyCommitted.add(pageId)
            }
        }
        runBlocking {
            delay(Random.nextLong(100))
        }
        txnManager.stop()

        recovery.run(walStorage, storage)
        certainlyCommitted.forEach { pageId ->
            val allRecords = storage.read(pageId * 10).allRecords()
            assertEquals(1, allRecords.size)
            println(allRecords)
            allRecords[0]!!.bytes.let { bytes ->
                assertEquals(pageId,  Record1(intField()).fromBytes(bytes).value1)
            }
        }
        println(wal)
    }
}

/**
 * This is a fake recovery process. It ignores the WAL Storage and just writes one record into 10 pages.
 */
private class FakeRecovery: Recovery {
    override fun run(walStorage: Storage, mainStorage: Storage) {
        (1..10).forEach { pageId ->
            val page = mainStorage.read(pageId * 10)
            page.clear()
            page.putRecord(Record1(intField(pageId)).asBytes())
            mainStorage.write(page)
        }
    }

}
