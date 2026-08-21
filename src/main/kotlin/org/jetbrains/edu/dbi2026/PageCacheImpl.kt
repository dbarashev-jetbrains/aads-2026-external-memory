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

import org.slf4j.LoggerFactory
import kotlin.math.round

internal data class StatsImpl(var cacheHitCount: Int = 0, var cacheMissCount: Int = 0): PageCacheStats {
    override val cacheHit: Int
        get() = cacheHitCount
    override val cacheMiss: Int
        get() = cacheMissCount
    override val hitRatio: Double
        get() = hitRatio(cacheHitCount, cacheHitCount+cacheMissCount)


    override fun reset() {
        cacheHitCount = 0
        cacheMissCount = 0
    }

    val addTracker = mutableMapOf<PageId, Int>()
    override fun toString(): String {
        return """Cache stats:
            hit ratio=${hitRatio} hits=$cacheHitCount misses=$cacheMissCount
            """
    }
}

fun hitRatio(hitCount: Int, totalCount: Int): Double = round(100.0*hitCount/(totalCount+0.0))
internal open class CachedPageImpl(
    internal val diskPage: DiskPage,
    var pinCount: Int = 1): CachedPage, DiskPage by diskPage {

    internal var _isDirty = false
    override val isDirty: Boolean get() = _isDirty

    override var usage = CachedPageUsage(0, System.currentTimeMillis())

    override fun putHeader(header: ByteArray) {
        if (!diskPage.getHeader().contentEquals(header)) {
            diskPage.putHeader(header).also {
                _isDirty = true
            }
        }
    }

    override fun putRecord(recordData: ByteArray, recordId: RecordId): PutRecordResult = diskPage.putRecord(recordData, recordId).also {
        _isDirty = true
    }

    override fun deleteRecord(recordId: RecordId) = diskPage.deleteRecord(recordId).also {
        _isDirty = true
    }

    override fun close() {
        if (pinCount > 0) {
            pinCount -= 1
        }
    }

    internal fun incrementUsage() {
        usage = CachedPageUsage(usage.accessCount + 1, System.currentTimeMillis())
    }
}

/**
 * This class implements a simple FIFO-like page cache. It is open, that is, allows for subclassing and overriding
 * some methods.
 */
open class SimplePageCacheImpl(internal val storage: Storage, private val maxCacheSize: Int = -1): PageCache {
    private val statsImpl = StatsImpl()
    override val stats: PageCacheStats get() = statsImpl
    override var monitoring: CacheMonitoring = NoMonitoringImpl()
    internal val cacheArray = mutableListOf<CachedPageImpl>()
    internal val cache get() = cacheArray.associateBy { it.id }

    override val capacity = maxCacheSize

    override fun load(startPageId: PageId, pageCount: Int) = doLoad(startPageId, pageCount, this::doAddPage)


    internal fun doLoad(startPageId: PageId, pageCount: Int, addPage: (page: DiskPage) -> CachedPageImpl) {
        storage.bulkRead(startPageId, pageCount) { diskPage ->
            val cachedPage = cache[diskPage.id] ?: addPage(diskPage)
            // We do not record cache hit or cache miss because load is a bulk operation and most likely it loads the
            // pages which are not yet cached. Recording cache miss will skew the statistics.
            onPageRequest(cachedPage, null)
        }
    }

    override fun get(pageId: PageId): CachedPage = doGetAndPin(
        pageId,
        this::doAddPage,
        0
    )

    override fun getAndPin(pageId: PageId): CachedPage = doGetAndPin(
        pageId,
        this::doAddPage
    )

    internal fun doGetAndPin(pageId: PageId, addPage: (page: DiskPage) -> CachedPageImpl, pinIncrement: Int = 1): CachedPageImpl {
        var cacheHit = true
        return cache.getOrElse(pageId) {
            cacheHit = false
            addPage(storage.read(pageId))
        }.also {
            onPageRequest(it, isCacheHit = cacheHit)
            it.pinCount += pinIncrement
        }
    }

    private fun doAddPage(page: DiskPage): CachedPageImpl {
        val result = CachedPageImpl(page, 0)
        if (cache.size == maxCacheSize) {
            swap(getEvictCandidate(), result)
        } else {
            synchronized(cacheArray) {
                this.cacheArray.add(result)
            }
        }
        statsImpl.addTracker[page.id] = (statsImpl.addTracker[page.id] ?: 0) + 1
        return result
    }

  override fun flush() {
        cache.forEach { (_, cachedPage) -> cachedPage.write() }
    }

    internal fun swap(victim: CachedPageImpl, newPage: CachedPageImpl) {
        victim.write()
        synchronized(cacheArray) { doSwap(victim, newPage) }
    }

    // By default we place the new page at the same index where the victim used to be.
    // Override this to implement your own swap policy.
    internal open fun doSwap(victim: CachedPageImpl, newPage: CachedPageImpl) {
        val idx = cacheArray.indexOf(victim)
        LOG.debug("swap: #${victim.id} => #${newPage.id} / @index=$idx")
        cacheArray[idx] = newPage
    }

    private fun recordCacheHit(isCacheHit: Boolean) =
        if (isCacheHit) statsImpl.cacheHitCount += 1 else statsImpl.cacheMissCount += 1

    internal fun CachedPageImpl.write() {
        if (this.isDirty) {
            storage.write(this.diskPage)
        }
        this._isDirty = false
    }
    // -------------------------------------------------------------------------------------------------------------
    // Override these functions to implement custom page replacement policy
    internal open fun getEvictCandidate(): CachedPageImpl {
        return cacheArray.firstOrNull {
            it.pinCount == 0
        } ?: throw IllegalStateException("All pages are pinned, there is no victim for eviction")
    }

    internal open fun onPageRequest(page: CachedPageImpl, isCacheHit: Boolean?) {
        isCacheHit?.let {
            recordCacheHit(it)
            monitoring.recordCacheResult(page.id, it)
        }
        page.incrementUsage()

    }
}

internal class NoCachedPageImpl(private val storage: Storage, diskPage: DiskPage) : CachedPageImpl(diskPage, 0) {
    override fun close() {
        if (this.isDirty) {
            storage.write(this.diskPage)
        }
        this._isDirty = false
        super.close()
    }
}

class NonePageCacheImpl(private val storage: Storage): PageCache {
    override fun load(startPageId: PageId, pageCount: Int) {
        storage.bulkRead(startPageId, pageCount) {
            // We do nothing with the pages that we read
        }
    }

    override fun get(pageId: PageId): CachedPage =
        NoCachedPageImpl(storage, storage.read(pageId))


    override fun getAndPin(pageId: PageId): CachedPage = get(pageId)

    override fun flush() {
        // No cache -- no flush
    }

    override var monitoring: CacheMonitoring = NoMonitoringImpl()
    private val statsImpl = StatsImpl()
    override val stats: PageCacheStats get() = statsImpl
    override val capacity: Int = Int.MAX_VALUE
}

/**
 * Implements a random eviction policy. A victim is any random unpinned page.
 */
class RandomPageCacheImpl(storage: Storage, maxCacheSize: Int): SimplePageCacheImpl(storage, maxCacheSize) {
    override fun getEvictCandidate(): CachedPageImpl {
        val unpinned = cacheArray.filter {
            it.pinCount == 0
        }
        if (unpinned.isNotEmpty()) return unpinned.random() else throw IllegalStateException("All pages are pinned, there is no victim for eviction")
    }
}

/**
 * Implements FIFO eviction policy. A victim is the first page in the ordered list of unpinned pages. A new page is inserted
 * at the end of the list.
 */
class FifoPageCacheImpl(storage: Storage, maxCacheSize: Int): SimplePageCacheImpl(storage, maxCacheSize) {
    override fun doSwap(victim: CachedPageImpl, newPage: CachedPageImpl) {
        LOG.debug("swap: #${victim.id} => #${newPage.id}")
        cacheArray.remove(victim)
        cacheArray.add(newPage)
    }
}

/**
 * Implements LRU eviction policy. A victim is the page that was used least recently. A shared logical counter that is incremented
 * on each access to the page is used as a timestamp.
 */
class LRUPageCacheImpl(storage: Storage, maxCacheSize: Int): SimplePageCacheImpl(storage, maxCacheSize) {
    private var accessTs = 0L

    override fun getEvictCandidate(): CachedPageImpl {
        return cacheArray.filter { it.pinCount == 0 }.minBy { it.usage.lastAccessTs } ?: throw IllegalStateException("All pages have the same last access timestamp, there is no victim for eviction")
    }

    override fun onPageRequest(page: CachedPageImpl, isCacheHit: Boolean?) {
        super.onPageRequest(page, isCacheHit)
        page.usage = CachedPageUsage(page.usage.accessCount, accessTs++)
    }
}

class NoMonitoringImpl: CacheMonitoring {
    override fun recordCacheResult(pageId: PageId, isCacheHit: Boolean) {
    }
}
private val LOG = LoggerFactory.getLogger("Cache.Impl")
