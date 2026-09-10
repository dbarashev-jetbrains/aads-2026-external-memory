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

import java.util.*
import java.util.function.Consumer

private var pageCount = 0

fun createDiskPage(): DiskPage = DiskPageImpl(pageCount++, DEFAULT_DISK_PAGE_SIZE)

/** Factory for creating [DiskPage] instances. */
interface DiskPageFactory {
    /**
     * Creates a new empty page with the given [pageId]. The returned page contains no records and
     * looks like a newly initialized page.
     */
    fun createEmptyPage(pageId: PageId): DiskPage

    /**
     * Creates a copy of the [proto] page. The new page will have the same content as [proto]. If
     * [pageId] is specified, the new page will have this id. Otherwise, it will have the same id as
     * [proto].
     */
    fun createCopyPage(proto: DiskPage, pageId: PageId? = null): DiskPage
}

val defaultDiskPageFactory: DiskPageFactory =
        object : DiskPageFactory {
            override fun createEmptyPage(pageId: PageId) =
                    DiskPageImpl(pageId, DEFAULT_DISK_PAGE_SIZE)

            override fun createCopyPage(proto: DiskPage, pageId: PageId?) =
                    DiskPageImpl(
                            pageId ?: proto.id,
                            DEFAULT_DISK_PAGE_SIZE,
                            proto.headerSize,
                            proto.rawBytes
                    )
        }
/**
 * Creates an emulator of a HDD with a single platter. All bulk reads and writes are blocking: at
 * any moment no more than one bulk operation is running, and other operations won't start until the
 * running one completes.
 */
fun createHardDriveEmulatorStorage(factory: DiskPageFactory = defaultDiskPageFactory): Storage =
        HardDiskEmulatorStorage(factory)

private class HardDiskEmulatorStorage(private val diskPageFactory: DiskPageFactory) : Storage {
    override val stats = StorageStatsImpl()
    private var accessCostMs = 0.0
    private val pageMap = TreeMap<PageId, DiskPage>()

    override val totalAccessCost: Double
        get() = accessCostMs

    private fun nextPageId() = if (pageMap.isEmpty()) 0 else pageMap.lastKey() + 1
    override fun read(pageId: PageId): DiskPage =
            doReadPage(pageId).also { countRandomAccess(pageId, false) }

    fun doReadPage(pageId: PageId): DiskPage =
            diskPageFactory.createCopyPage(
                    pageMap.getOrPut(pageId) { diskPageFactory.createEmptyPage(pageId) }
            )

    override fun bulkRead(startPageId: PageId, numPages: Int, reader: Consumer<DiskPage>) {
        val realStartPageId = if (startPageId == -1) nextPageId() else startPageId
        (realStartPageId until realStartPageId + numPages).forEach { reader.accept(doReadPage(it)) }
        countRandomAccess(realStartPageId)
        countSeqScan(numPages)
    }

    override fun write(page: DiskPage) {
        if (page.id < 0) {
            throw IllegalArgumentException("Illegal page ID = ${page.id}")
        }
        pageMap[page.id] = diskPageFactory.createCopyPage(page)
        countRandomAccess(page.id, true)
    }

    class BulkWriteImpl(
            private val storageImpl: HardDiskEmulatorStorage,
            override val startPageId: PageId
    ) : BulkWrite {
        var numPages = 0
        var currentPageId = startPageId

        override fun write(inPage: DiskPage): DiskPage =
                storageImpl.diskPageFactory.createCopyPage(inPage, currentPageId).also {
                    storageImpl.pageMap[currentPageId] = it
                    numPages++
                    currentPageId++
                }

        override fun close() {
            storageImpl.countSeqScan(numPages)
        }
    }

    override fun bulkWrite(startPageId: PageId): BulkWrite {
        var nextKey = if (startPageId == -1) nextPageId() else startPageId
        if (nextKey < 0) {
            throw IllegalArgumentException("Illegal start page ID = $nextKey")
        }
        countRandomAccess(nextKey)
        return BulkWriteImpl(this, nextKey)
    }

    private fun countRandomAccess(pageId: PageId, isWrite: Boolean = false) {
        accessCostMs += 5.0
        stats.countRandomAccess(isWrite)
    }

    private fun countSeqScan(numPages: Int) {
        // 5400 rotations per minute = 90 rotations per second = 1 rotation per 0.0111 seconds
        // 1 page is 8 sectors and there are 64 sectors per track => there are 8 pages per track
        // => we need 1/8 rotation = 1.3ms to read the whole page
        accessCostMs += 1.3 * numPages
    }

    override fun close() {}
}

class StorageStatsImpl : StorageStats {
    private var _randomReadCount = 0L
    private var _randomWriteCount = 0L
    private var _seqScanCount = 0L

    override val totalAccessCost: Double
        get() = TODO("Not yet implemented")

    override val randomReadCount: Long
        get() = _randomReadCount

    override val randomWriteCount: Long
        get() = _randomWriteCount

    internal fun countRandomAccess(isWrite: Boolean = false) {
        if (isWrite) {
            _randomWriteCount++
        } else {
            _randomReadCount++
        }
    }

    override fun reset() {
        _randomReadCount = 0L
        _randomWriteCount = 0L
        _seqScanCount = 0L
    }

    override fun toString(): String {
        return "StorageStatsImpl(randomReadCount=$randomReadCount, randomWriteCount=$randomWriteCount)"
    }
}
