package com.basefinder.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventStoreTest {

    private fun obj(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @Test
    fun ingestStoresAndCounts() {
        val store = EventStore(capacity = 10)
        store.ingest(obj("""{"type":"bot_tick","seq":1}"""))
        store.ingest(obj("""{"type":"base_found","seq":2}"""))

        assertEquals(2, store.stored())
        assertEquals(2L, store.totalReceived())
    }

    @Test
    fun snapshotReturnsRecentEvents() {
        val store = EventStore(capacity = 10)
        repeat(5) { i -> store.ingest(obj("""{"seq":$i}""")) }

        val snap = store.snapshot(3)
        assertEquals(3, snap.size)
        // Last 3 events: seq 2, 3, 4
        assertEquals("2", snap[0]["seq"].toString())
        assertEquals("4", snap[2]["seq"].toString())
    }

    @Test
    fun snapshotReturnsAllWhenLimitExceedsStored() {
        val store = EventStore(capacity = 10)
        repeat(3) { i -> store.ingest(obj("""{"seq":$i}""")) }

        val snap = store.snapshot(100)
        assertEquals(3, snap.size)
    }

    @Test
    fun capacityDropsOldestEvents() {
        val store = EventStore(capacity = 3)
        repeat(5) { i -> store.ingest(obj("""{"seq":$i}""")) }

        // Stored = 3 (oldest 2 dropped), counter still tracks total received
        assertEquals(3, store.stored())
        assertEquals(5L, store.totalReceived())

        val snap = store.snapshot(10)
        assertEquals("2", snap[0]["seq"].toString())
        assertEquals("4", snap[2]["seq"].toString())
    }

    @Test
    fun threadSafetyUnderConcurrentIngest() {
        val store = EventStore(capacity = 10_000)
        val threads = List(10) { tid ->
            Thread {
                repeat(100) { i -> store.ingest(obj("""{"tid":$tid,"i":$i}""")) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1000L, store.totalReceived())
        assertTrue(store.stored() <= 1000)
    }
}
