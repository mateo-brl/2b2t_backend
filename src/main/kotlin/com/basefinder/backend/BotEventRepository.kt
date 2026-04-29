package com.basefinder.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insertIgnore
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
 */
class BotEventRepository {

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
        return if (rowId > 0) InsertOutcome.INSERTED else InsertOutcome.DUPLICATE
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

    fun totalReceived(): Long = totalReceived.get()
}
