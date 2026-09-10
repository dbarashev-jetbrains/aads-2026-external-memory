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

import org.jetbrains.edu.dbi2026.*
import org.jetbrains.edu.dbi2026.catalog.CatalogPageFactoryImpl
import org.jetbrains.edu.dbi2026.fake.*
import org.slf4j.LoggerFactory
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success

/**
 * Feel free to change this code and add your own factories.
 */
fun initializeFactories(
    storage: Storage,
    directoryStorage: Storage = createHardDriveEmulatorStorage(CatalogPageFactoryImpl()),
    cacheSize: Int = (System.getProperty("cache.size") ?: "100").toInt(),
    cacheImpl: CacheAlgorithm = CacheAlgorithm.fromString(System.getProperty("cache.impl", "fifo")),
    sortImpl: String = System.getProperty("sort.impl", "fake"),
    hashImpl: String = System.getProperty("hash.impl", "fake"),
    indexImpl: String = System.getProperty("index.impl", "fake"),
    optimizerImpl: String = System.getProperty("optimizer.impl", "fake"),
    walImpl: String = System.getProperty("wal.impl", "fake"),
): Pair<PageCache, StorageAccessManager> {
    LOGGER.debug("=".repeat(80))
    LOGGER.debug("Cache policy: $cacheImpl")
    LOGGER.debug("Cache size: $cacheSize")
    CacheManager.factory = { strg, size ->
        when (cacheImpl) {
            CacheAlgorithm.NONE  -> NonePageCacheImpl(strg)
            CacheAlgorithm.RANDOM -> RandomPageCacheImpl(strg, size)
            CacheAlgorithm.LRU -> LRUPageCacheImpl(strg, size)
            CacheAlgorithm.CLOCK -> TODO("Create your clock cache instance here")
            CacheAlgorithm.AGING -> TODO("Create your aging cache instance here")
            CacheAlgorithm.FIFO -> FifoPageCacheImpl(strg, size)
        }
    }
    Operations.sortFactory = { strg, cache ->
        when (sortImpl) {
            "real" -> TODO("Create your merge sort instance here")
            else -> TopKSortImpl(strg, cache)
        }
    }
    Operations.hashFactory = { storageAccessManager, pageCache ->
        when (hashImpl) {
            "real" -> TODO("Create your hash builder instance here")
            else -> FakeHashTableBuilder(storageAccessManager, pageCache)
        }
    }
    Operations.innerJoinFactory = { accessMethodManager, pageCache, joinAlgorithm ->
        when (joinAlgorithm) {
            JoinAlgorithm.NESTED_LOOPS -> success(BlockNestedLoops(accessMethodManager, pageCache))
            JoinAlgorithm.HASH -> failure(NotImplementedError("Hash-Join not implemented yet"))
            JoinAlgorithm.MERGE -> failure(NotImplementedError("Sort-Merge-Join not implemented yet"))
        }
    }
    Indexes.indexFactory = { storageAccessManager, cache ->
        when (indexImpl) {
            "real" -> TODO("Create your index manager instance here")
            else -> FakeIndexManager(storageAccessManager, cache)
        }
    }
    walFactory = {
        when (walImpl) {
            "real" -> TODO("Create your WAL factory instance here")
            else -> FakeWAL()
        }
    }
    recoveryFactory = {
        when (walImpl) {
            "real" -> TODO("Create your recovery factory instance here")
            else -> NoRecovery()
        }
    }

    Optimizer.factory = { storageAccessManager, pageCache ->
        when (optimizerImpl) {
            "real" -> TODO("Create your optimizer here")
            else -> VoidOptimizer()
        }
    }

    val cache = CacheManager.factory(storage, cacheSize)
    val simpleAccessManager = SimpleStorageAccessManager(cache, directoryStorage)

    return cache to simpleAccessManager
}

private val LOGGER = LoggerFactory.getLogger("Initialize")
