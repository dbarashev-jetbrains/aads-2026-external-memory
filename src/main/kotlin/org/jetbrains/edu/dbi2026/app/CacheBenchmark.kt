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
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import org.jetbrains.edu.dbi2026.*
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.max

class CacheBenchmark: CliktCommand() {
    val dataScale: Int by option(help="Test data scale").int().default(1)
    val cacheSize: Int by option(help="Page cache size").int().default(System.getProperty("cache.size", "100").toInt())
    val cacheImpl by option(help="Cache implementation").enum<CacheAlgorithm>().default(CacheAlgorithm.fromString(System.getProperty("cache.impl", "fifo")))
    val testFullScanChance: Double by option(help="The probability of a full scan of flight table").double().default(0.1)
    val testLongPinChance: Double by option(help="The probability of a page to stay pinned after it was accessed").double().default(0.01)
    val testHotPageCount: Int by option(help="The number of hot pages that are accessed very frequently").int().default(10)
    val testWarmPageCount: Int by option(help="The number of warm pages that are accessed often").int().default(50)
    override fun run() {
        val storage = createHardDriveEmulatorStorage()
        val (cache, accessManager) = initializeFactories(storage = storage, cacheSize = cacheSize, cacheImpl = cacheImpl)
        DataGenerator(accessManager, cache, dataScale, fixedRowCount = true).use{}

        val planetPages = accessManager.pageCount("planet")
        val spacecraftPages = accessManager.pageCount("spacecraft")
        val flightPageCount = accessManager.pageCount("flight")
        val ticketPageCount = accessManager.pageCount("ticket")

        val ticketPages = accessManager.createFullScan("ticket").pages().map { it.close(); it.id }.toList()
        val flightPages = accessManager.createFullScan("flight").pages().map { it.close(); it.id }.toList()
        val pageWorkingSet = (ticketPages + flightPages).shuffled().toList()
        val hotWorkingSet = pageWorkingSet.subList(0, testHotPageCount)
        val warmWorkingSet = pageWorkingSet.subList(0, testWarmPageCount)

        LOG.info("Page count: planet=$planetPages spacecraft=$spacecraftPages flight=$flightPageCount ticket=$ticketPageCount working set for the random access: ${pageWorkingSet.size}")
        val cacheMonitoring = CacheMonitoringImpl(accessManager, hotWorkingSet, warmWorkingSet)

        cache.stats.reset()
        cache.flush()
        cache.monitoring = cacheMonitoring

        val accessCost1 = storage.totalAccessCost
        val random = Random().also { it.setSeed(System.currentTimeMillis()) }
        var fullScanCount = 0
        var longPinCount = 0
        var maxLongPinCount = 0
        var randomPageAccess = 0
        var randomPageCacheHit = 0

        val hotPages = mutableSetOf<PageId>()
        val accessedPages = mutableSetOf<PageId>()
        val pageAccessCounts = mutableMapOf<PageId, Int>()
        val pinnedPages = mutableListOf<CachedPage>()
        val meanBetweenFullScans = MeanValueCalculator()

        repeat(200 * dataScale) {
            // With some probability we will do a full scan of a table, that will possibly replace a significant portion of the cache.
            if (random.nextDouble() < testFullScanChance) {
                meanBetweenFullScans.nextRun()
                LOG.debug("---- full scan! ----")
                accessManager.createFullScan("planet").pages().forEach { it.close() }
                fullScanCount += 1
                LOG.debug("---- end of full scan! ----")
                return@repeat
            }
            meanBetweenFullScans.inc()
            // Sometimes we will close some of the long-pinned pages
            if (pinnedPages.isNotEmpty() && random.nextInt(10) == 1) {
                val donePages = pinnedPages.subList(0, random.nextInt(pinnedPages.size))
                donePages.forEach { it.close() }
                donePages.clear()
            }

            val accessType = random.nextInt(10)
            val pageId = when {
                accessType < 3 -> pageWorkingSet.random()
                accessType in 3..<6 -> warmWorkingSet.random()
                else -> hotWorkingSet.random().also {
                        hotPages.add(it)
                    }

            }

            pageAccessCounts.getOrPut(pageId) { 0 }
            pageAccessCounts[pageId] = pageAccessCounts[pageId]!! + 1

            accessedPages.add(pageId)
            randomPageAccess += 1
            val numHitBefore = cache.stats.cacheHit
            val page = cache.getAndPin(pageId)
            if (cache.stats.cacheHit > numHitBefore) {
                randomPageCacheHit += 1
            }
            // With some probability we will "long pin" the page, as if there is a relatively long transaction
            // that modifies the page.
            if (pinnedPages.size < cacheSize - 1 && random.nextDouble() < testLongPinChance) {
                if (pinnedPages.firstOrNull { it.id == pageId } == null) {
                    pinnedPages.add(page)
                    longPinCount += 1
                    maxLongPinCount = max(maxLongPinCount, pinnedPages.size)
                }
            } else {
                page.close()
            }
        }
        pinnedPages.forEach { it.close() }
        LOG.info("""
           | 
           | ====================================================
           | Total number of random reads: $randomPageAccess
           | Number of distinct pages accessed randomly: ${accessedPages.size}
           | Long-pins:
           |   - number of long-pinned pages totally: $longPinCount
           |   - max. number of long-pinned pages: $maxLongPinCount
           | 
           | Full scans:
           |   - number of full scans: $fullScanCount
           |   - random reads between full scans: mean=${meanBetweenFullScans.mean()} max=${meanBetweenFullScans.max()} min=${meanBetweenFullScans.min()}
           | 
           | Cache hits and misses: 
           |   - overall hit ratio: ${cache.stats.hitRatio}
           |   - random reads across all pages: ${hitRatio(randomPageCacheHit, randomPageAccess)}
           |   - by specific tables:
                ${cacheMonitoring.buildReport()}
                
           | ACCESS COST: ${storage.totalAccessCost - accessCost1}
        """.trimMargin())
        LOG.debug("page access: ${pageAccessCounts.entries.sortedBy { it.value }.reversed()}")
    }
}

private val LOG = LoggerFactory.getLogger("Cache.Benchmark")
private class MeanValueCalculator {
    private var totalSum = 0
    var runSum = 0
    var runCount = 0
    private var maxRunSum = 0
    private var minRunSum = 0

    fun inc() {
        runSum++
    }

    fun nextRun() {
        totalSum += runSum
        if (runSum > maxRunSum) {
            maxRunSum = runSum
        }
        if (runSum < minRunSum || minRunSum == 0) {
            minRunSum = runSum
        }
        runSum = 0
        runCount++
    }

    fun mean(): Double = totalSum / runCount.toDouble()
    fun max() = maxRunSum
    fun min() = minRunSum
}

class CacheMonitoringImpl(
    private val accessManager: StorageAccessManager,
    private val hotWorkingSet: List<PageId>,
    private val warmWorkingSet: List<PageId>
): CacheMonitoring {
    private val pageIdToTableName = mutableMapOf<PageId, String>()
    private val tableNameToCacheResult = mutableMapOf<String, Pair<Int, Int>>()
    private var hotPagesCacheResult = 0 to 0
    private var warmPagesCacheResult = 0 to 0

    private fun collectTablePages(tableName: String) {
        accessManager.createFullScan(tableName).pages().forEach { page ->
            pageIdToTableName[page.id] = tableName
            page.close()
        }
    }

    override fun recordCacheResult(pageId: PageId, isCacheHit: Boolean) {
        val tableName = pageIdToTableName[pageId] ?: return
        val (hitCount, missCount) = tableNameToCacheResult.getOrPut(tableName) { 0 to 0 }
        tableNameToCacheResult[tableName] = if (isCacheHit) {
            hitCount + 1 to missCount
        } else {
            hitCount to missCount + 1
        }
        if (pageId in hotWorkingSet) {
            hotPagesCacheResult = if (isCacheHit) {
                hotPagesCacheResult.first + 1 to hotPagesCacheResult.second
            } else {
                hotPagesCacheResult.first to hotPagesCacheResult.second + 1
            }
        }
        if (pageId in warmWorkingSet) {
            warmPagesCacheResult = if (isCacheHit) {
                warmPagesCacheResult.first + 1 to warmPagesCacheResult.second
            } else {
                warmPagesCacheResult.first to warmPagesCacheResult.second + 1
            }
        }
    }

    init {
        listOf("planet", "spacecraft", "flight", "ticket").forEach { collectTablePages(it) }
    }

    fun buildReport(): String {
        val builder = StringBuilder()
        tableNameToCacheResult.entries.forEach { (tableName, result) ->
            builder.appendLine("|     $tableName: hit ratio=${hitRatio(result.first, result.first+result.second)}")
        }
        builder.appendLine("|     Hot pages: hit ratio=${hitRatio(hotPagesCacheResult.first, hotPagesCacheResult.first+hotPagesCacheResult.second)}")
        builder.appendLine("|     Warm pages: hit ratio=${hitRatio(warmPagesCacheResult.first, warmPagesCacheResult.first+warmPagesCacheResult.second)}")
        return builder.toString()
    }

}
