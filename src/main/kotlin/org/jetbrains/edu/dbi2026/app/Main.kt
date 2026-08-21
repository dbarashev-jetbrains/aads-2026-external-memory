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
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import net.datafaker.Faker
import org.jetbrains.edu.dbi2026.*
import org.jetbrains.edu.dbi2026.catalog.CatalogPageFactoryImpl
import java.io.File
import java.nio.file.Path
import kotlin.random.Random

fun main(args: Array<String>) = Main().subcommands(SmokeTest(), CacheBenchmark(), SortBenchmark(), HashBenchmark(), JoinBenchmark(),
    OptimizerBenchmark(), Warmup()).main(args)

class Main: CliktCommand() {
    init {
        // Use Mordant-based help formatter to show default values in help output
        context {
            // Configure Mordant help formatter to include default values in option help
            helpFormatter = { ctx -> com.github.ajalt.clikt.output.MordantHelpFormatter(ctx, showDefaultValues = true) }
        }
    }
    override fun run() = Unit
}

class SmokeTest: CliktCommand() {
    val dataDir: File? by option(help="Path to the data directory").file(mustExist = false, canBeDir = true, canBeFile = false)
    val generateData by option(help="Generate data").flag(default = false)
    val cacheSize: Int by option(help="Page cache size").int().default(System.getProperty("cache.size", "100").toInt())
    val cacheImpl by option(help="Cache implementation").enum<CacheAlgorithm>().default(CacheAlgorithm.fromString(System.getProperty("cache.impl", "fifo")))
    val sortImpl: String by option(help="Merge sort implementation").default(System.getProperty("sort.impl", "fake"))
    val hashImpl: String by option(help="Hash table implementation").default(System.getProperty("hash.impl", "fake"))
    val indexImpl: String by option(help="Indexes implementation").default(System.getProperty("index.impl", "fake"))
    val walImpl: String by option(help="WAL implementation").default(System.getProperty("wal.impl", "fake"))
    val optimizerImpl: String by option(help="Optimizer implementation").default(System.getProperty("optimizer.impl", "fake"))

    val dataScale: Int by option(help="Test data scale").int().default(1)
    val randomDataSize by option(help="Shall the generated data amount be random").flag(default = false)
    val joinClause: String by option(help="JOIN clause, e.g. 'planet.id:flight.planet_id'").default("")
    val filterClause: String by option(help="Filter clause, e.g. 'planet.id = 1'").default("")
    val indexClause: String by option(help="Index clause to create indexes before running a query, e.g. flight.num").default("")
    val enableStatistics by option(help="Enable collection of attribute statistics").flag(default = false)

    override fun run() {
        val storage = dataDir?.let { createMappedFileStorage(baseDir = Path.of(it.path), factory = CatalogPageFactoryImpl()) } ?: createHardDriveEmulatorStorage(factory = CatalogPageFactoryImpl())
        val (cache, accessManager) = initializeFactories(
            storage = storage,
            directoryStorage = storage,
            cacheSize = cacheSize,
            cacheImpl = cacheImpl,
            sortImpl = sortImpl,
            hashImpl = hashImpl,
            indexImpl = indexImpl,
            optimizerImpl = optimizerImpl,
            walImpl = walImpl
        )

        if (generateData) {
            DataGenerator(accessManager, cache, dataScale, !randomDataSize).use {}
            val populateCost = storage.totalAccessCost
            println("The cost to populate tables: ${populateCost}")
        }
        if (enableStatistics) {
            println("--------------------")
            println("Collecting statistics")
            Statistics.managerInstance = generateStatistics(storageAccessManager = accessManager, cache = cache)
            println("Done!")
            println("--------------------")
            println(Statistics.managerInstance)
        }

        accessManager.buildIndexes(parseIndexClause(indexClause))

        val costAfterIndexes = storage.totalAccessCost
        println("Access cost after creation of tables and indexes: $costAfterIndexes")

        if (joinClause.isNotBlank() || filterClause.isNotBlank()) {
            executeQueryPlan(accessManager, cache, storage, joinClause, filterClause)
        } else {
            printTableContents(accessManager, storage)
        }
        accessManager.close()
        cache.flush()
        storage.close()
    }

    fun printTableContents(accessManager: StorageAccessManager, storage: Storage) {
        val cost0 = storage.totalAccessCost
        println("Now we will print the contents of all tables")

        println("Planet table:")
        accessManager.createFullScan("planet").records { planetRecord(it) }.forEach { println(it) }

        println()
        println("Spacecraft table:")
        accessManager.createFullScan("spacecraft").records { spacecraftRecord(it) }.forEach { println(it) }

        println()
        println("Flight table:")
        accessManager.createFullScan("flight").records { flightRecord(it) }.forEach { println(it) }

        println()
        println("Ticket table:")
        accessManager.createFullScan("ticket").records { ticketRecord(it) }.forEach { println(it) }
        println("Total cost: ${storage.totalAccessCost}. Scan cost: ${storage.totalAccessCost - cost0}")
    }
}

fun StorageAccessManager.buildIndexes(specs: List<IndexSpec>) {
    specs.forEach { spec ->
        val attributeType = attributeTypes["${spec.tableName}.${spec.attributeName}"] ?: throw IllegalStateException("Can't find attribute type for $spec")
        val attributeValueParser = attributeValueParsers["${spec.tableName}.${spec.attributeName}"] ?: throw IllegalStateException("Can't find attribute value parser for $spec")
        println("Creating index $spec")
        createIndex(spec.tableName, spec.attributeName, attributeType, attributeValueParser)
    }
}

fun executeQueryPlan(accessManager: StorageAccessManager, cache: PageCache, storage: Storage, joinClause: String, filterClause: String, printResults: Boolean = true) {
    val cost0 = storage.totalAccessCost
    val innerJoins = parseJoinClause(joinClause)
    val filters = parseFilterClause(filterClause)
    val plan = Optimizer.factory(accessManager, cache).buildPlan(QueryPlan(innerJoins, filters))
    println("We're executing the following query plan: $plan")

    var rowCount = 0
    val joinResult = QueryExecutor(accessManager, cache, tableRecordParsers, attributeValueParsers).run {
        execute(plan)
    }
    if (printResults) {
        val joinedTables = joinResult.realTables
        accessManager.createFullScan(joinResult.tableName).records { bytes ->
            parseJoinedRecord(bytes, joinedTables, tableRecordParsers)
        }.forEach {
            rowCount++
            it.entries.forEach { (tableName, recordBytes) ->
                println("$tableName: ${tableRecordParsers[tableName]!!.invoke(recordBytes)}")
            }
            println("----")
        }
    }
    joinResult.close()
}

fun generateStatistics(storageAccessManager: StorageAccessManager, cache: PageCache): StatisticsManager =
    Statistics.managerFactory(storageAccessManager, cache).apply {
        buildAttributeStatistics(
            "planet",
            "id",
            intField().first,
            10
        ) { planetRecord(it).component1() }
        buildAttributeStatistics(
            "planet",
            "name",
            stringField().first,
            10
        ) { planetRecord(it).component2() }
        buildAttributeStatistics(
            "planet",
            "distance",
            doubleField().first,
            10
        ) { planetRecord(it).component3() }
        buildAttributeStatistics(
            "spacecraft",
            "id",
            intField().first,
            10
        ) { spacecraftRecord(it).component1() }
        buildAttributeStatistics(
            "spacecraft",
            "name",
            stringField().first,
            10
        ) { spacecraftRecord(it).component2() }
        buildAttributeStatistics(
            "spacecraft",
            "capacity",
            intField().first,
            10
        ) { spacecraftRecord(it).component3() }
        buildAttributeStatistics(
            "flight",
            "num",
            intField().first,
            10
        ) { flightRecord(it).component1() }
        buildAttributeStatistics(
            "flight",
            "planet_id",
            intField().first,
            10
        ) { flightRecord(it).component2() }
        buildAttributeStatistics(
            "flight",
            "spacecraft_id",
            intField().first,
            10
        ) { flightRecord(it).component3() }
        buildAttributeStatistics(
            "ticket",
            "flight_num",
            intField().first,
            10
        ) { ticketRecord(it).component1() }
        buildAttributeStatistics(
            "ticket",
            "pax_name",
            stringField().first,
            10
        ) { ticketRecord(it).component2() }
        buildAttributeStatistics(
            "ticket",
            "price",
            doubleField().first,
            10
        ) { ticketRecord(it).component3() }

        buildTableStatistics("planet", ::planetRecord)
        buildTableStatistics("spacecraft", ::spacecraftRecord)
        buildTableStatistics("flight", ::flightRecord)
        buildTableStatistics("ticket", ::ticketRecord)
    }


class Warmup: CliktCommand() {
    override fun run() {
        val faker = Faker()
        val storage = createHardDriveEmulatorStorage(factory = CatalogPageFactoryImpl())
        val (cache, accessManager) = initializeFactories(storage)
        val tableOid: Oid = 1 // TODO: create a table using API and get its OID
        val tableBuilder = TableBuilder(accessManager, cache, tableOid) // TODO: write implementation of TableBuilder methods

        // We are inserting 100 Planet records into the table.
        repeat(100) {idx ->
            PlanetRecord(intField(idx), stringField(faker.starCraft().planet()), doubleField(Random.nextDouble(100.0, 900.0))).also {
                tableBuilder.insert(it.asBytes())
            }
        }

        // TODO: write the code that scans the table and prints all records.

    }

}
