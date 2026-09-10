package org.jetbrains.edu.dbi2026

import java.util.PriorityQueue
import java.util.function.Function
import kotlin.random.Random

/**
 * An external sort built around an in-memory top-k structure. It is deliberately NOT a Multiway-Merge sort.
 *
 * The algorithm repeatedly scans the whole input:
 *
 * - a bounded max-heap keeps the k smallest records seen during the current scan;
 * - when the scan is over, the largest record of the heap becomes the "high water mark" and the heap contents
 *   are appended to the output table in the ascending order;
 * - the next scan only feeds the heap with the records above the high water mark, and so on. As soon as a scan
 *   finds nothing above the high water mark, the whole input has been written out and the sort is done.
 *
 * The cost is O(N/k) scans of the input, that is, it grows *linearly* with the data size,
 *
 * Note on the ordering: records are compared by the pair (value, position in the scan), not by the value alone.
 * Comparing by the value alone would lose records: if more than k records share the high water mark value, the
 * next scan would filter all of them out with its "greater than the high water mark" condition, and the ones
 * that did not fit into the heap would never be written. The scan order is stable across the scans, so the pair
 * is a total order, and every record is written out exactly once.
 */
class TopKSortImpl(
    private val storageAccessManager: StorageAccessManager,
    private val cache: PageCache,
    private val k: Int = 1000
) : MultiwayMergeSort {

    private val temporaryTables = mutableListOf<String>()
    /**
     * How many pages are read ahead with a single bulk read. One page is reserved for the output table page
     * which the table builder keeps pinned, and one more for the table directory pages.
     */
    private val readAheadChunkSize = (cache.capacity - 2).coerceAtLeast(1)

    override fun <T : Comparable<T>> sort(tableName: String, comparableValue: Function<ByteArray, T>): String {
        val (outTable, outTableOid) = createOutputTable()
        val inputPageIds = inputPageIds(tableName)
        // The largest record which has already been written to the output.
        var highWaterMark: Candidate<T>? = null
        TableBuilder(storageAccessManager, cache, outTableOid).use { builder ->
            while (true) {
                val topK = PriorityQueue<Candidate<T>>(k, reverseOrder())
                // Position of the record in the scan. Along with the record value it makes the total order.
                var scanPosition = 0L
                // Did the top-k structure ever have to drop a record because it was full?
                var isTopKOverflow = false
                scanInput(tableName, inputPageIds) { page ->
                    page.allRecords().values.forEach { record ->
                        if (record.isOk) {
                            val candidate = Candidate(comparableValue.apply(record.bytes), scanPosition, record.bytes)
                            if (admit(topK, candidate, highWaterMark)) {
                                isTopKOverflow = true
                            }
                            scanPosition += 1
                        }
                    }
                }
                if (topK.isEmpty()) {
                    // Nothing above the high water mark, so the whole input is written out.
                    break
                }
                highWaterMark = topK.peek()
                topK.sorted().forEach { builder.insert(it.bytes) }
                if (!isTopKOverflow) {
                    // The structure has never been full, so it has accepted every record above the high water
                    // mark, and we have just written all of them. No need to scan the input once again only
                    // to find out that there is nothing left.
                    break
                }
            }
        }
        return outTable
    }

    /**
     * Feeds a single record to the top-k structure, unless the record has already been written to the output
     * or is not among the k smallest records of this scan.
     *
     * @return true if the structure was full and some record had to be dropped, which means that one scan
     *         is not enough to write out all the remaining records.
     */
    private fun <T : Comparable<T>> admit(
        topK: PriorityQueue<Candidate<T>>,
        candidate: Candidate<T>,
        highWaterMark: Candidate<T>?
    ): Boolean {
        if (highWaterMark != null && candidate <= highWaterMark) {
            return false
        }
        if (topK.size < k) {
            topK.add(candidate)
            return false
        }
        if (candidate < topK.peek()) {
            topK.poll()
            topK.add(candidate)
        }
        return true
    }

    /**
     * Scans the input table and calls the consumer for every input page. If the input page ids are already known,
     * uses bulk reads of the consecutive pages, otherwise falls back to the regular full scan.
     */
    private fun scanInput(tableName: String, pageIds: List<PageId>?, consumer: (CachedPage) -> Unit) {
        if (pageIds == null) {
            storageAccessManager.createFullScan(tableName).pages().forEach(consumer)
        } else {
            pageIds.readAheadChunks(readAheadChunkSize).forEach { chunk ->
                // A single bulk read of the whole chunk, so the subsequent get() calls are cache hits.
                cache.load(chunk.first(), chunk.size)
                chunk.forEach { consumer(cache.get(it)) }
            }
        }
    }

    /**
     * Reads the ids of the input table pages from the table page directory, so that every scan, including the
     * very first one, could use bulk reads.
     *
     * @return the input page ids or null if the table can't be found, in which case the caller falls back
     *         to the regular full scan
     */
    private fun inputPageIds(tableName: String): List<PageId>? =
        try {
            storageAccessManager.tablePages(tableName)
        } catch (ex: AccessMethodException) {
            null
        }

    /**
     * Splits the page ids into the chunks of consecutive ids, no longer than maxChunkSize pages, so that
     * every chunk could be read with a single bulk read into the cache.
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

    private fun createOutputTable(): Pair<String, Oid> =
        generateSequence { "topksort_${Random.nextLong().toULong()}" }
            .first { !storageAccessManager.tableExists(it) }
            .let { it to storageAccessManager.createTable(it) }

    private fun deleteTemporaryTable(tableName: String) {
        storageAccessManager.deleteTable(tableName)
        temporaryTables.remove(tableName)
    }

    override fun close() {
        temporaryTables.toList().forEach { deleteTemporaryTable(it) }
    }
}

/**
 * A record competing for a place in the top-k structure. Records are ordered by their values first and by their
 * positions in the scan second, which makes the order total even when the values are equal.
 */
private class Candidate<T : Comparable<T>>(
    private val value: T,
    private val scanPosition: Long,
    val bytes: ByteArray
) : Comparable<Candidate<T>> {

    override fun compareTo(other: Candidate<T>): Int =
        value.compareTo(other.value).let {
            if (it != 0) it else scanPosition.compareTo(other.scanPosition)
        }
}
