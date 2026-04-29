package com.basefinder.backend

import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SseRoutes")

/**
 * GET /v1/events/stream — Server-Sent Events.
 *
 * Each new event inserted into the DB is pushed as a single SSE message.
 * The dashboard does an initial REST fetch to populate the historical
 * window, then subscribes here for live updates.
 *
 * Heartbeats: Ktor's SSE plugin keeps the connection open with periodic
 * comments by default. Browsers auto-reconnect on close (EventSource).
 */
fun Route.sseRoutes(broadcaster: EventBroadcaster) {
    sse("/v1/events/stream") {
        log.info("SSE client connected")
        // Flush HTTP headers (200 + CORS) immediately. Without this, Ktor
        // waits for the first real event to write the response head — the
        // browser then sees no Access-Control-Allow-Origin and aborts the
        // EventSource as a CORS error before any data flows.
        send(ServerSentEvent(comments = "ready"))
        try {
            broadcaster.events.collect { event ->
                val key = (event["idempotency_key"]?.toString() ?: "").trim('"')
                send(
                    ServerSentEvent(
                        data = event.toString(),
                        event = "event",
                        id = key.ifEmpty { null }
                    )
                )
            }
        } catch (e: Throwable) {
            log.debug("SSE client disconnected: {}", e.message)
        }
    }
}

@Suppress("unused")
private suspend fun ServerSSESession.heartbeat() {
    // Future: emit ping comments if Ktor's default keepalive isn't enough.
}
