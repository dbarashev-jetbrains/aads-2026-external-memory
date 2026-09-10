package org.jetbrains.edu.dbi2026.catalog

import org.jetbrains.edu.dbi2026.*

class TablePageRecords(
    private val pageCache: PageCache,
    private val tableId: Oid
): Iterable<OidPageidRecord> {

    override fun iterator(): Iterator<OidPageidRecord> = TablePageRecordIteratorImpl(pageCache, tableId)
}

private val TABLE_PAGE_RECORD = OidPageidRecord(intField(), intField())

internal class TablePageRecordIteratorImpl(
    private val pageCache: PageCache,
    private val tableId: PageId): FullScanIteratorBase<OidPageidRecord>(TABLE_PAGE_RECORD::fromBytes) {

    private var currentCatalogPageId = -1

    init {
         currentCatalogPageId = tableId
        advance()
    }

    override fun advancePage(): CachedPage? {
        if (currentCatalogPageId == -1) {
            return null
        }
        val page = pageCache.get(currentCatalogPageId)
        return CatalogPageHeader.read(page).let {
            currentCatalogPageId = it.nextPageId
            page
        }
    }
}
