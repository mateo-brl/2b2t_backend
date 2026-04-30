package com.basefinder.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
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
            // Side effect : pour chunks_scanned_batch, on hydrate aussi la table
            // dédiée scanned_chunks. Idempotent grâce au PK composite.
            if (type == "chunks_scanned_batch") {
                hydrateScannedChunks(event, now)
            }
            broadcaster?.publish(event)
        } else if (outcome == InsertOutcome.DUPLICATE && type == "bot_tick") {
            // Le bot recommence ses idempotency_key (tick:0, tick:1...) à chaque
            // restart MC, donc les ticks de la nouvelle session entrent en
            // collision avec ceux de la précédente et seraient sinon avalés
            // silencieusement. On rebroadcast pour que le dashboard suive
            // toujours la position live ; les consommateurs dédupent eux-mêmes.
            broadcaster?.publish(event)
        }
        return outcome
    }

    /**
     * Décompose un batch chunks_scanned_batch en lignes scanned_chunks.
     * Les clés sont des longs packed (32 bits x | 32 bits z) — mêmes
     * conventions que ChunkId.pack côté bot.
     */
    private fun hydrateScannedChunks(event: JsonObject, now: Long) {
        val dim = event["dimension"]?.jsonPrimitive?.contentOrNull ?: return
        val arr = event["chunks"]?.jsonArray ?: return
        if (arr.isEmpty()) return
        transaction {
            ScannedChunksTable.batchInsert(arr, ignore = true) { el ->
                val packed = el.jsonPrimitive.longOrNull ?: return@batchInsert
                val cx = (packed shr 32).toInt()
                val cz = packed.toInt()
                this[ScannedChunksTable.dim] = dim
                this[ScannedChunksTable.chunkX] = cx
                this[ScannedChunksTable.chunkZ] = cz
                this[ScannedChunksTable.scannedAt] = now
            }
        }
    }

    data class CoverageCell(val cx: Int, val cz: Int, val count: Int)

    /**
     * Agrège les chunks scannés en une grille de cellules. La taille de cellule
     * (en chunks par côté) contrôle le grain : 1 = chunk-level, 64 = ~1 km côté.
     *
     * Filtre par bbox (en world blocks) pour ne renvoyer que ce que le viewport
     * voit — économise du JSON et du rendu.
     */
    fun coverage(
        dim: String,
        gridChunks: Int,
        xMinBlocks: Int,
        xMaxBlocks: Int,
        zMinBlocks: Int,
        zMaxBlocks: Int,
    ): List<CoverageCell> = transaction {
        val cxMin = xMinBlocks shr 4
        val cxMax = xMaxBlocks shr 4
        val czMin = zMinBlocks shr 4
        val czMax = zMaxBlocks shr 4
        val agg = HashMap<Long, Int>()
        for (row in ScannedChunksTable
            .selectAll()
            .where {
                (ScannedChunksTable.dim eq dim)
                    .and(ScannedChunksTable.chunkX greaterEq cxMin)
                    .and(ScannedChunksTable.chunkX lessEq cxMax)
                    .and(ScannedChunksTable.chunkZ greaterEq czMin)
                    .and(ScannedChunksTable.chunkZ lessEq czMax)
            }) {
            val cx = row[ScannedChunksTable.chunkX]
            val cz = row[ScannedChunksTable.chunkZ]
            val gx = Math.floorDiv(cx, gridChunks)
            val gz = Math.floorDiv(cz, gridChunks)
            val key = (gx.toLong() shl 32) or (gz.toLong() and 0xFFFFFFFFL)
            agg[key] = (agg[key] ?: 0) + 1
        }
        agg.entries.map { (k, v) ->
            val gx = (k shr 32).toInt()
            val gz = k.toInt()
            CoverageCell(gx, gz, v)
        }
    }

    fun coverageCount(dim: String): Long = transaction {
        ScannedChunksTable.selectAll().where { ScannedChunksTable.dim eq dim }.count()
    }

    /**
     * Supprime un événement par sa clé d'idempotence. Utilisé par le dashboard
     * pour retirer un faux-positif depuis le popup d'une base. Renvoie
     * {@code true} si une ligne a été affectée, {@code false} sinon.
     */
    fun deleteByKey(key: String): Boolean = transaction {
        BotEventsTable.deleteWhere { it.run { BotEventsTable.idempotencyKey eq key } } > 0
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
