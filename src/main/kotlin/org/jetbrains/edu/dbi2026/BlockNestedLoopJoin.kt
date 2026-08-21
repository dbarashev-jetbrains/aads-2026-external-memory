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

/**
 * Implements nested loops join operation. Iterator returned by this class is lazy: it evaluates the join operation only
 * when the iterator is consumed. It does not create any intermediate data structures.
 */
class BlockNestedLoops(private val storageAccessManager: StorageAccessManager, private val pageCache: PageCache): InnerJoin {
    override fun <T : Comparable<T>> join(leftTable: JoinOperand<T>, rightTable: JoinOperand<T>): JoinOutput {
        return BlockNestedLoopsOutputIterator(leftTable, rightTable, storageAccessManager, pageCache)
    }
}

private class BlockNestedLoopsOutputIterator<T : Comparable<T>>(
    private val leftTable: JoinOperand<T>,
    private val rightTable: JoinOperand<T>,
    private val storageAccessManager: StorageAccessManager,
    private val pageCache: PageCache): JoinOutput {

    private val leftTableChunks = ChunkedPageIterator<T>(
        storageAccessManager.createFullScan(leftTable.tableName),
        (pageCache.capacity/2).coerceAtLeast(1),
        leftTable.joinAttribute
    )
    private var leftRecordIterator: Iterator<Pair<T, ByteArray>> = emptyList<Pair<T, ByteArray>>().iterator()
    private var rightTableIterator: Iterator<Pair<T, ByteArray>> = emptyList<Pair<T, ByteArray>>().iterator()
    private var currentRightRecord: Pair<T, ByteArray>? = null
    private var currentLeftRecord: Pair<T, ByteArray>? = null

    private var nextOutput: Pair<ByteArray, ByteArray>? = null

    init {
        advance()
    }
    override fun next(): Pair<ByteArray, ByteArray> {
        val result = nextOutput ?: throw NoSuchElementException()
        advance()
        return result
    }

    override fun hasNext(): Boolean {
        return nextOutput != null
    }

    override fun close() {
    }

    private fun advanceRight(rewind: Boolean = true): Boolean {
        assert(!leftRecordIterator.hasNext())

        if (rightTableIterator.hasNext()) {
            currentRightRecord = rightTableIterator.next()
            return true
        }
        if (!rewind) {
            return false
        }
        rightTableIterator = storageAccessManager.createFullScan(rightTable.tableName).records { rightBytes ->
            rightTable.joinAttribute.apply(rightBytes) to rightBytes
        }.iterator()
        return if (rightTableIterator.hasNext()) {
            currentRightRecord = rightTableIterator.next()
            true
        } else {
            false
        }
    }

    private fun rewindLeft(): Boolean {
        leftRecordIterator = leftTableChunks.records()
        return if (leftRecordIterator.hasNext()) {
            currentLeftRecord = leftRecordIterator.next()
            true
        } else false
    }

    private fun advance() {
        var result = null as Pair<ByteArray, ByteArray>?
        while (result == null) {
            if (currentRightRecord == null) {
                // We are just initializing NLJ
                if (!advanceRight()) {
                    // No records in right table at all
                    nextOutput = null
                    return
                }
                if (!rewindLeft()) {
                    // No records in left table at all
                    nextOutput = null
                    return
                }
                // At this point we have a valid record in right table and left table
            } else {
                // We have a current right record. Let's try to get the next left one.
                if (leftRecordIterator.hasNext()) {
                    // Okay, we have a next record in the left chunk
                    currentLeftRecord = leftRecordIterator.next()
                } else {
                    // The left chunk is exhausted. We try to advance the right iterator and rewind the left one.
                    if (!advanceRight(rewind = false)) {
                        // Both the left chunk and the right iterator are exhausted. Let's try to load the next chunk.
                        if (!leftTableChunks.advance()) {
                            // No more left chunks. We are done.
                            nextOutput = null
                            return
                        }
                        if (!advanceRight()) {
                            // No records in right table at all
                            nextOutput = null
                            return
                        }
                    }
                    // We have a valid record in right table. Let's try to rewind the left one.
                    if (!rewindLeft()) {
                        // We are out of records in the left chunk.
                        nextOutput = null
                    }
                }
            }
            // We have a current left and right record. Let's compare them.
            if (currentLeftRecord!!.first == currentRightRecord!!.first) {
                // We have a match. Let's output the record.
                result = currentLeftRecord!!.second to currentRightRecord!!.second
            }
        }
        nextOutput = result
    }
}

private class ChunkedPageIterator<T : Comparable<T>>(fullScan: FullScan, private val chunkSize: Int, private val joinAttribute: java.util.function.Function<ByteArray, T>) {
    private val iterator = fullScan.pages().iterator()
    private val currentChunk = mutableListOf<CachedPage>()

    init {
        advance()
    }

    fun advance(): Boolean {
        currentChunk.clear()
        while (iterator.hasNext() && currentChunk.size < chunkSize) {
            currentChunk.add(iterator.next())
        }
        return currentChunk.isNotEmpty()
    }

    fun records(): Iterator<Pair<T, ByteArray>> {
        return ChunkedPageRecords(currentChunk, joinAttribute).iterator()
    }
}
