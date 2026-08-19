package net.barashev.dbi2023

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TopKSortTest {
    private fun sorted(input: List<Int>, cacheSize: Int, k: Int): List<Int> {
        val storage = createHardDriveEmulatorStorage()
        val cache = FifoPageCacheImpl(storage, cacheSize)
        val am = SimpleStorageAccessManager(cache)
        val oid = am.createTable("foo")
        TableBuilder(am, cache, oid).use { b -> input.forEach { b.insert(intField().first.asBytes(it)) } }
        return TopKSortImpl(am, cache, k).use { sorter ->
            val out = sorter.sort("foo") { intField().first.fromBytes(it).first }
            am.createFullScan(out).records { intField().first.fromBytes(it).first }.toList()
        }
    }

    @Test
    fun `distinct keys, many passes`() {
        val input = (1..5000).shuffled()
        assertEquals(input.sorted(), sorted(input, cacheSize = 10, k = 300))
    }

    @Test
    fun `duplicates far exceeding k must not be lost`() {
        // 4000 records share one single value while k is only 100.
        val input = (List(4000) { 42 } + List(500) { 7 } + List(500) { 99 }).shuffled()
        val actual = sorted(input, cacheSize = 10, k = 100)
        assertEquals(input.size, actual.size)
        assertEquals(input.sorted(), actual)
    }

    @Test
    fun `single pass is enough`() {
        val input = (1..50).shuffled()
        assertEquals(input.sorted(), sorted(input, cacheSize = 20, k = 1000))
    }

    @Test
    fun `empty input`() {
        assertEquals(emptyList(), sorted(emptyList(), cacheSize = 10, k = 1000))
    }

    @Test
    fun `all records identical`() {
        val input = List(3000) { 5 }
        assertEquals(input, sorted(input, cacheSize = 5, k = 250))
    }
}
