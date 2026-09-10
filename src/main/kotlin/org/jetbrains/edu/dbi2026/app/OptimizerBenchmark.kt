package org.jetbrains.edu.dbi2026.app

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import org.jetbrains.edu.dbi2026.CacheAlgorithm
import org.jetbrains.edu.dbi2026.Statistics
import org.jetbrains.edu.dbi2026.TableBuilder
import org.jetbrains.edu.dbi2026.createHardDriveEmulatorStorage
import kotlin.random.Random

class OptimizerBenchmark: CliktCommand() {
    val dataScale: Int by option(help="Test data scale").int().default(10)
    val realOptimizer by option(help="Use the real optimizer implementation").flag(default = false)
    val cacheSize: Int by option(help="Page cache size").int().default(System.getProperty("cache.size", "100").toInt())
    val cacheImpl by option(help="Cache implementation").enum<CacheAlgorithm>().default(CacheAlgorithm.fromString(System.getProperty("cache.impl", "fifo")))
    val indexClause: String by option(help="Index clause to create indexes before running a query, e.g. flight.num").default("")
    val printResults: Boolean by option(help="Print the query results").flag("--no-print-results", default = true)

    override fun run() {
        val storage = createHardDriveEmulatorStorage()
        val (cache, accessManager) = initializeFactories(storage = storage, cacheSize = cacheSize,
            cacheImpl = cacheImpl,
            optimizerImpl = if (realOptimizer) "real" else "fake"
        )
        DataGenerator(accessManager, cache, dataScale, fixedRowCount = true).use{}

        val planetPages = accessManager.pageCount("planet")
        val spacecraftPages = accessManager.pageCount("spacecraft")
        val flightPageCount = accessManager.pageCount("flight")
        val ticketPageCount = accessManager.pageCount("ticket")

        val cancelledFlightTable = accessManager.createTable("cancelled_flight")
        TableBuilder(accessManager, cache, cancelledFlightTable).use { builder ->
            accessManager.createFullScan("flight").records{ bytes -> flightRecord(bytes) }.forEach { flight ->
                if (Random.nextInt(1, 10) == 1) {
                    builder.insert(flight.asBytes())
                }
            }
        }

        println("Page count: planet=$planetPages spacecraft=$spacecraftPages flight=$flightPageCount ticket=$ticketPageCount cancelled flight=${accessManager.pageCount("cancelled_flight")}")
        Statistics.managerInstance = generateStatistics(storageAccessManager = accessManager, cache = cache)
        cache.stats.reset()
        storage.stats.reset()

        accessManager.buildIndexes(parseIndexClause(indexClause))
        storage.stats.reset()
        cache.flush()
        println("Query 1: SELECT * FROM planet JOIN flight JOIN ticket WHERE flight.num < 5 AND ticket.price < 200")
        executeQueryPlan(accessManager, cache, storage, "planet.id:flight.planet_id flight.num:ticket.flight_num", "flight.num < 5 & ticket.price < 200", printResults)
        println("Query 2: SELECT * FROM planet JOIN flight JOIN ticket JOIN cancelled_flight")
        executeQueryPlan(accessManager, cache, storage, "planet.id:flight.planet_id flight.num:ticket.flight_num flight.num:cancelled_flight.num", "", printResults)
        println("Query 3: SELECT * FROM planet JOIN flight JOIN ticket WHERE flight.num = 42")
        executeQueryPlan(accessManager, cache, storage, "planet.id:flight.planet_id flight.num:ticket.flight_num", "flight.num = 42", printResults)
        println("DISK: ${storage.stats.randomReadCount + storage.stats.randomWriteCount}")
    }
}
