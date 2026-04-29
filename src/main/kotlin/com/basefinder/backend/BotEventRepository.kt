package com.basefinder.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists incoming events. Returns one of:
 * - {@link InsertOutcome.INSERTED} — first time we see this idempotency key.
 * - {@link InsertOutcome.DUPLICATE} — key already known (silent dedupe).
 * - {@link InsertOutcome.MALFORMED} — payload is missing required fields
 *   (type/seq/idempotency_key); event is dropped.
 *
 * Uses {@code INSERT OR IGNORE} (via Exposed {@code insertIgnore}) for
 * race-safe dedupe at the DB layer.
 *
 * On a successful INSERT, the event is also published to the optional
 * {@link EventBroadcaster} so SSE subscribers see it live.
 */
class BotEventRepository(private val broadcaster: EventBroadcaster? = null) {

    private val totalReceived = AtomicLong(0)
    private val parser = Json { ignoreUnknownKeys = true }

    enum class InsertOutcome { INSERTED, DUPLICATE, MALFORMED }

    fun ingest(event: JsonObject): InsertOutcome {
        totalReceived.incrementAndGet()
        val type = event["type"]?.jsonPrimitive?.contentOrNull
        val seq = event["seq"]?.jsonPrimitive?.longOrNull
        val ts = event["ts_utc_ms"]?.jsonPrimitive?.longOrNull
        val idempotencyKey = event["idempotency_key"]?.jsonPrimitive?.contentOrNull
        if (type == null || seq == null || ts == null || idempotencyKey == null) {
            return InsertOutcome.MALFORMED
        }

        val raw = event.toString()
        val now = System.currentTimeMillis()
        val rowId = transaction {
            BotEventsTable.insertIgnore {
                it[BotEventsTable.seq] = seq
                it[tsUtcMs] = ts
                it[BotEventsTable.type] = type
                it[BotEventsTable.idempotencyKey] = idempotencyKey
                it[payload] = raw
                it[receivedAt] = now
            }.insertedCount
        }
        val outcome = if (rowId > 0) InsertOutcome.INSERTED else InsertOutcome.DUPLICATE
        if (outcome == InsertOutcome.INSERTED) {
            broadcaster?.publish(event)
        }
        return outcome
    }

    fun recent(limit: Int): List<JsonObject> = transaction {
        BotEventsTable
            .selectAll()
            .orderBy(BotEventsTable.id, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                parser.parseToJsonElement(row[BotEventsTable.payload]) as JsonObject
            }
            .reversed()
    }

    fun count(): Long = transaction {
        BotEventsTable.selectAll().count()
    }

    /**
     * Bases detected, optionally filtered by dimension and minimum score.
     * Returns the raw event payloads (the dashboard parses chunk_x/score/etc).
     * Newest first (DESC by id), capped by [limit].
     */
    fun bases(dimension: String?, minScore: Double?, limit: Int): List<JsonObject> = transaction {
        val q = BotEventsTable
            .selectAll()
            .where { BotEventsTable.type eq "base_found" }
            .orderBy(BotEventsTable.id, SortOrder.DESC)
            .limit(limit * 4) // over-fetch then filter in app code (SQLite has no JSON ops)

        val out = ArrayList<JsonObject>(limit)
        for (row in q) {
            val obj = parser.parseToJsonElement(row[BotEventsTable.payload]) as JsonObject
            if (dimension != null) {
                val dim = obj["dimension"]?.jsonPrimitive?.contentOrNull
                if (dim != dimension) continue
            }
            if (minScore != null) {
                val score = obj["score"]?.jsonPrimitive?.doubleOrNull
                if (score == null || score < minScore) continue
            }
            out.add(obj)
            if (out.size >= limit) break
        }
        out
    }

    fun totalReceived(): Long = totalReceived.get()
}
