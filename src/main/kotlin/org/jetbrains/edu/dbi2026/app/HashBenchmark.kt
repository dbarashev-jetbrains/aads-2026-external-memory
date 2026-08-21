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

package org.jetbrains.edu.dbi2026.app

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import org.jetbrains.edu.dbi2026.*
import org.jetbrains.edu.dbi2026.fake.*

class HashBenchmark: CliktCommand() {
    val dataScale: Int by option(help="Test data scale").int().default(1)
    val cacheSize: Int by option(help="Page cache size").int().default(System.getProperty("cache.size", "100").toInt())
    val cacheImpl by option(help="Cache implementation").enum<CacheAlgorithm>().default(CacheAlgorithm.fromString(System.getProperty("cache.impl", "fifo")))
    val realHash by option(help="Use the real hash implementation").flag()
    val hashBucketCount by option(help = "How many buckets in the hash table [default=cache size/2]").int()
    override fun run() {
        val storage = createHardDriveEmulatorStorage()
        val (cache, accessManager) = initializeFactories(storage = storage, cacheSize = cacheSize,
            cacheImpl = cacheImpl,
            hashImpl = if (realHash) "real" else "fake"
        )
        DataGenerator(accessManager, cache, dataScale, fixedRowCount = true).use{
            it.insertTicket(42, "Foo Bar", 42.0)
            it.insertTicket(56, "Foo Bar", 56.0)
        }

        val planetPages = accessManager.pageCount("planet")
        val spacecraftPages = accessManager.pageCount("spacecraft")
        val flightPageCount = accessManager.pageCount("flight")
        val ticketPageCount = accessManager.pageCount("ticket")

        val ticketPages = accessManager.createFullScan("ticket").pages().map { it.close(); it.id }.toList()

        println("Page count: planet=$planetPages spacecraft=$spacecraftPages flight=$flightPageCount ticket=$ticketPageCount")
        println(cache.stats)
        cache.stats.reset()

        val cost0 = storage.totalAccessCost
        val hashTable = Operations.hashFactory(accessManager, cache).hash("ticket", bucketCount = hashBucketCount ?: (cacheSize/2)) {
            ticketRecord(it).value2
        }
        val cost1 = storage.totalAccessCost
        println("Hashing done. Access cost=${cost1-cost0}")
        println(cache.stats)

        val realHashResults = hashTable.find("Foo Bar").map { ticketRecord(it) }.toSet()
        val cost2 = storage.totalAccessCost
        println("Flights made by Foo Bar: $realHashResults")
        println("The cost of searching in a hashtable: ${cost2 - cost1}")
        val fakeHashTable = FakeHashTableBuilder(accessManager, cache).hash("ticket", bucketCount = hashBucketCount ?: (cacheSize/2)) {
            ticketRecord(it).value2
        }
        val fakeHashResults = fakeHashTable.find("Foo Bar").map { ticketRecord(it) }.toSet()
        if (fakeHashResults != realHashResults) {
            error("""
                Wow, the results of searching in a real and in the fake hash tables are different!
                Real: $realHashResults
                Fake: $fakeHashResults
                """.trimIndent())
        }
        val fullScanResults = accessManager.createFullScan("ticket").records(::ticketRecord).filter { it.value2 == "Foo Bar" }.toSet()
        val cost3 = storage.totalAccessCost
        if (realHashResults != fullScanResults) {
            error("""
                Wow, the results of searching in a hash table and in a full scan are different!
                Hash table: $realHashResults
                Full scan: $fullScanResults                
            """.trimIndent())
        }
        println("The cost of a full scan search: ${cost3 - cost2}")
        if (cost3-cost2 < cost2-cost1) {
            error("""
                Wow, the cost of a full scan seems to be lower than a hash table lookup!
                Full scan: ${cost3 - cost2}
                Hash table: ${cost2 - cost1}
            """.trimIndent())
        }

    }
}
