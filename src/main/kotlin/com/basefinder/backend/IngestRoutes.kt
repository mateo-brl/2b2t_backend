package com.basefinder.backend

import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("IngestRoutes")
private val parser = Json { ignoreUnknownKeys = true }

@Serializable
data class IngestResponse(val accepted: Int, val duplicate: Int, val rejected: Int)

@Serializable
data class EventsResponse(val total: Long, val returned: Int, val events: List<JsonObject>)

fun Route.ingestRoutes(repo: BotEventRepository) {

    /**
     * NDJSON streaming ingest. Each line = one BotEvent (JSON object).
     * Empty lines skipped. Dedup is at the DB layer via UNIQUE
     * idempotency_key — reposting the same event returns "duplicate", not
     * "rejected".
     */
    post("/v1/ingest") {
        var accepted = 0
        var duplicate = 0
        var rejected = 0
        call.receiveStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    val obj = parser.parseToJsonElement(trimmed) as? JsonObject
                    if (obj == null) {
                        rejected++
                        continue
                    }
                    when (repo.ingest(obj)) {
                        BotEventRepository.InsertOutcome.INSERTED -> accepted++
                        BotEventRepository.InsertOutcome.DUPLICATE -> duplicate++
                        BotEventRepository.InsertOutcome.MALFORMED -> rejected++
                    }
                } catch (e: Exception) {
                    rejected++
                    log.debug("Rejected line: {}", e.message)
                }
            }
        }
        if (accepted > 0 || duplicate > 0) {
            log.info(
                "Ingested {} events ({} duplicates, {} rejected, {} stored, {} total)",
                accepted, duplicate, rejected, repo.count(), repo.totalReceived()
            )
        }
        call.respond(IngestResponse(accepted, duplicate, rejected))
    }

    /**
     * Debug endpoint: returns the last N events stored in the DB.
     * Default 100, max 1000.
     */
    get("/v1/events") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        val events = repo.recent(limit)
        call.respond(EventsResponse(
            total = repo.totalReceived(),
            returned = events.size,
            events = events
        ))
    }
}
