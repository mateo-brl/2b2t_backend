package com.basefinder.backend

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory ring of recent BotEvents. Thread-safe.
 *
 * Phase 0 of Jalon 2: no DB yet. The persistence layer (Postgres + Exposed)
 * comes next. This ring lets us validate the wire path end-to-end (bot →
 * HTTP → backend → /v1/events read) without DB infra cost.
 *
 * Cap is intentionally low (10 000) so RAM stays bounded if the bot streams
 * for hours without restart. Old events are dropped silently — debug-only.
 */
class EventStore(private val capacity: Int = DEFAULT_CAPACITY) {

    private val queue = ConcurrentLinkedDeque<JsonObject>()
    private val counter = AtomicLong(0)

    fun ingest(event: JsonObject) {
        queue.addLast(event)
        counter.incrementAndGet()
        while (queue.size > capacity) {
            queue.pollFirst()
        }
    }

    fun snapshot(limit: Int): List<JsonObject> {
        val list = queue.toList()
        return if (list.size <= limit) list else list.subList(list.size - limit, list.size)
    }

    fun totalReceived(): Long = counter.get()

    fun stored(): Int = queue.size

    companion object {
        const val DEFAULT_CAPACITY = 10_000
        val shared: EventStore = EventStore()
    }
}
