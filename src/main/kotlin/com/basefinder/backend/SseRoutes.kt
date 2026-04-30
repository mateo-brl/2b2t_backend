package com.basefinder.backend

import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SseRoutes")

private const val HEARTBEAT_INTERVAL_MS = 30_000L

/**
 * GET /v1/events/stream — Server-Sent Events.
 *
 * Each new event inserted into the DB is pushed as a single SSE message.
 * The dashboard does an initial REST fetch to populate the historical
 * window, then subscribes here for live updates.
 *
 * Heartbeat: a `:hb` SSE comment is emitted every 30 s in parallel with
 * the event collect loop. Without that, an idle SSE connection (no
 * events for >60 s) is silently dropped by some intermediaries
 * (NGINX `proxy_read_timeout`, browser tabs put to sleep, corporate
 * proxies). Comments are ignored by EventSource consumers but force
 * a network frame so the connection stays warm.
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
            coroutineScope {
                val heartbeat = async {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        send(ServerSentEvent(comments = "hb"))
                    }
                }
                broadcaster.events.collect { event ->
                    val key = (event["idempotency_key"]?.toString() ?: "").trim('"')
                    send(
                        ServerSentEvent(
                            data = event.toString(),
                            event = "event",
                            id = key.ifEmpty { null },
                        ),
                    )
                }
                heartbeat.cancel()
            }
        } catch (e: Throwable) {
            log.debug("SSE client disconnected: {}", e.message)
        }
    }
}
