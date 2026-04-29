package com.basefinder.backend

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Schema {@code bot_events}.
 *
 * - {@code idempotency_key} UNIQUE : drops duplicate POSTs (bot retries on
 *   reconnect) at the DB layer. Faster than checking in app code, and
 *   correct under concurrent inserts.
 * - {@code payload} : full event JSON, stored as TEXT (SQLite has no native
 *   JSON column; Postgres migration will switch this to JSONB without code
 *   change since we only read/write opaquely here).
 * - Index on {@code received_at} for the recent-events read path.
 */
object BotEventsTable : LongIdTable("bot_events") {
    val seq = long("seq")
    val tsUtcMs = long("ts_utc_ms")
    val type = varchar("type", 64).index()
    val idempotencyKey = varchar("idempotency_key", 128).uniqueIndex()
    val payload = text("payload")
    val receivedAt = long("received_at").index()
}
