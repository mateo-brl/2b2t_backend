package com.basefinder.backend

import io.ktor.http.HttpStatusCode
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
data class IngestResponse(val accepted: Int, val rejected: Int)

@Serializable
data class EventsResponse(val total: Long, val returned: Int, val events: List<JsonObject>)

fun Route.ingestRoutes(store: EventStore) {

    /**
     * NDJSON streaming ingest. Each line = one BotEvent (JSON object).
     * Empty lines are skipped. Malformed lines are counted as rejected
     * but don't fail the request — the bot may be writing concurrently.
     */
    post("/v1/ingest") {
        var accepted = 0
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
                    store.ingest(obj)
                    accepted++
                } catch (e: Exception) {
                    rejected++
                    log.debug("Rejected line: {}", e.message)
                }
            }
        }
        if (accepted > 0) {
            log.info("Ingested {} events ({} rejected, {} stored, {} total)",
                accepted, rejected, store.stored(), store.totalReceived())
        }
        call.respond(IngestResponse(accepted, rejected))
    }

    /**
     * Debug endpoint: returns the last N events stored in memory.
     * Default 100, max 1000.
     */
    get("/v1/events") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        val events = store.snapshot(limit)
        call.respond(EventsResponse(
            total = store.totalReceived(),
            returned = events.size,
            events = events
        ))
    }
}
