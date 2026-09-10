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
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import org.jetbrains.edu.dbi2026.*
import org.jetbrains.edu.dbi2026.fake.InMemorySort

class SortBenchmark: CliktCommand() {
    val dataScale: Int by option(help="Test data scale").int().default(1)
    val cacheSize: Int by option(help="Page cache size").int().default(System.getProperty("cache.size", "100").toInt())
    val cacheImpl by option(help="Cache implementation").enum<CacheAlgorithm>().default(CacheAlgorithm.fromString(System.getProperty("cache.impl", "fifo")))
    val sortAlgorithm by option(help="Use the real multiway merge sort implementation").default("topk")

    override fun run() {
        val storage = createHardDriveEmulatorStorage()
        val (cache, accessManager) = initializeFactories(storage = storage, cacheSize = cacheSize,
            cacheImpl = cacheImpl,
            sortImpl = System.getProperty("sort.impl") ?: sortAlgorithm
        )
        DataGenerator(accessManager, cache, dataScale, fixedRowCount = true).use{}

        val planetPages = accessManager.pageCount("planet")
        val spacecraftPages = accessManager.pageCount("spacecraft")
        val flightPageCount = accessManager.pageCount("flight")
        val ticketPageCount = accessManager.pageCount("ticket")

        val ticketPages = accessManager.createFullScan("ticket").pages().map { it.close(); it.id }.toList()

        println("Page count: planet=$planetPages spacecraft=$spacecraftPages flight=$flightPageCount ticket=$ticketPageCount")
        cache.stats.reset()

        var sortCost = 0.0
        val realSortMin = Operations.sortFactory(accessManager, cache).use { sorter ->
            println("Starting the 'real' merge sort...")
            val cost0 = storage.totalAccessCost
            val sortedTicketsTable = sorter.sort("ticket") {
                ticketRecord(it).value3
            }
            println("Sorting completed")
            sortCost = storage.totalAccessCost - cost0
            println(cache.stats)
            accessManager.createFullScan(sortedTicketsTable).records(::ticketRecord).first()
        }

        val fakeSortMin = InMemorySort(accessManager, cache).use {
            val fakeSortedTicketsTable = it.sort("ticket") { ticketRecord(it).value3 }
            accessManager.createFullScan(fakeSortedTicketsTable).records(::ticketRecord).first()
        }

        println("The cheapest ticket found by the 'real' merge sort: $realSortMin")
        println("The cheapest ticket found by the in-memory sort: $fakeSortMin")
        if (realSortMin.value3 != fakeSortMin.value3) {
            error("The results of the 'real' and 'in-memory' sorts seem to be different!")
        }
        println("ACCESS COST: $sortCost")
    }
}
