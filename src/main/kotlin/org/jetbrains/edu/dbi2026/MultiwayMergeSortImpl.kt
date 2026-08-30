package org.jetbrains.edu.dbi2026

import java.util.PriorityQueue
import java.util.function.Function
import kotlin.random.Random

class MultiwayMergeSortImpl(
    private val storageAccessManager: StorageAccessManager, private val cache: PageCache) : MultiwayMergeSort {

    private val tempTables = mutableListOf<String>()

    override fun <T : Comparable<T>> sort(
        tableName: String,
        comparableValue: Function<ByteArray, T>
    ): String {
        val runs = generateRuns(tableName, comparableValue)
        return mergeRuns(runs, comparableValue)
    }

    private fun <T : Comparable<T>> mergeRuns(runs: List<String>, comparableValue: Function<ByteArray, T>): String {
        val fanIn = cache.capacity - 1
        var currentRuns = runs
        while (currentRuns.size > 1) {
            currentRuns = currentRuns.chunked(fanIn).map { batch -> mergeBatch(batch, comparableValue) }
        }
        return currentRuns.first()
    }

    private fun <T : Comparable<T>> mergeBatch(batch: List<String>, comparableValue: Function<ByteArray, T>): String {
        val keyedParser = Function<ByteArray, Pair<T, ByteArray>> { comparableValue.apply(it) to it }
        val iterators = batch.map { storageAccessManager.createFullScan(it).records(keyedParser).iterator() }

        val heap = PriorityQueue<IndexedValue<Pair<T, ByteArray>>>(compareBy { it.value.first })
        iterators.forEachIndexed { idx, it -> if (it.hasNext()) heap.add(IndexedValue(idx, it.next())) }

        val outName = "merge_${Random.nextLong()}"
        val outOid = storageAccessManager.createTable(outName)
        TableBuilder(storageAccessManager, cache, outOid).use { b ->
            while (heap.isNotEmpty()) {
                val (idx, headValue) = heap.poll()
                b.insert(headValue.second)
                val it = iterators[idx]
                if (it.hasNext()) heap.add(IndexedValue(idx, it.next()))
            }
        }
        // The input runs are fully consumed now, so free them immediately instead of waiting for close().
        batch.forEach { runName ->
            storageAccessManager.deleteTable(runName)
            tempTables.remove(runName)
        }
        tempTables.add(outName)
        return outName
    }

    /**
     * Generates sorted runs using replacement selection: a min-heap tagged with a "generation" (which run a
     * record belongs to) always yields the smallest record of the current run first. Every time a record is
     * emitted, the next input record is pulled in immediately - it joins the current run's generation if its key
     * is still >= the last emitted key, or gets tagged for the next run's generation otherwise. This produces
     * runs that are, on average, twice as long as a plain "buffer, sort, flush" approach would, which means
     * fewer runs and therefore fewer merge passes downstream.
     */
    private fun <T : Comparable<T>> generateRuns(tableName: String, comparableValue: Function<ByteArray, T>): List<String> {
        val runs = mutableListOf<String>()
        val runCapacityPages = cache.capacity - 1

        val pageBatches = storageAccessManager.tablePages(tableName).readAheadChunks(runCapacityPages).asSequence()
            .flatMap { chunk ->
                cache.load(chunk.first(), chunk.size)
                chunk.asSequence().map { pageId -> cache.get(pageId).allRecords().values.filter { it.isOk } }
            }.iterator()

        var pagesConsumed = 0
        var currentPageRecords: Iterator<GetRecordResult> = emptyList<GetRecordResult>().iterator()
        fun nextInput(): ByteArray? {
            while (!currentPageRecords.hasNext()) {
                if (!pageBatches.hasNext()) return null
                currentPageRecords = pageBatches.next().iterator()
                pagesConsumed++
            }
            return currentPageRecords.next().bytes
        }

        val heap = PriorityQueue<RSEntry<T>>(compareBy({ it.generation }, { it.key }))
        // Prime the heap with roughly one run-capacity's worth of pages, same memory budget as before.
        while (pagesConsumed < runCapacityPages) {
            val bytes = nextInput() ?: break
            heap.add(RSEntry(0, comparableValue.apply(bytes), bytes))
        }

        var currentGeneration = 0
        var lastEmitted: T? = null
        var builder: TableBuilder? = null

        fun startNewRun(generation: Int) {
            builder?.close()
            val runName = "run_${Random.nextLong()}"
            val runOid = storageAccessManager.createTable(runName)
            builder = TableBuilder(storageAccessManager, cache, runOid)
            runs.add(runName)
            tempTables.add(runName)
            currentGeneration = generation
            lastEmitted = null
        }

        while (heap.isNotEmpty()) {
            val top = heap.poll()
            if (builder == null || top.generation != currentGeneration) {
                startNewRun(top.generation)
            }
            builder!!.insert(top.bytes)
            lastEmitted = top.key

            val nextBytes = nextInput()
            if (nextBytes != null) {
                val nextKey = comparableValue.apply(nextBytes)
                val generation = if (nextKey >= lastEmitted!!) currentGeneration else currentGeneration + 1
                heap.add(RSEntry(generation, nextKey, nextBytes))
            }
        }
        builder?.close()
        return runs
    }

    /**
     * Splits the page ids into chunks of consecutive ids, no longer than maxChunkSize pages, so that
     * every chunk can be read with a single bulk read into the cache.
     */
    private fun List<PageId>.readAheadChunks(maxChunkSize: Int): List<List<PageId>> {
        val chunks = mutableListOf<MutableList<PageId>>()
        forEach { pageId ->
            val lastChunk = chunks.lastOrNull()
            if (lastChunk == null || lastChunk.size == maxChunkSize || lastChunk.last() + 1 != pageId) {
                chunks.add(mutableListOf(pageId))
            } else {
                lastChunk.add(pageId)
            }
        }
        return chunks
    }

    override fun close() {
        tempTables.forEach { storageAccessManager.deleteTable(it) }
        tempTables.clear()
    }
}

/**
 * A replacement-selection heap entry. Generation is compared first so that every record belonging to the
 * current run is drained from the heap before any record tagged for the next run, regardless of key value.
 */
private class RSEntry<T : Comparable<T>>(val generation: Int, val key: T, val bytes: ByteArray)