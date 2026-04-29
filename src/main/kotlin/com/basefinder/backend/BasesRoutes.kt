package com.basefinder.backend

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class BasesResponse(val total: Int, val bases: List<JsonObject>)

/**
 * Bases-only read API for the dashboard map. Filters server-side by
 * dimension and minimum score. The dashboard pulls the initial set on
 * mount and listens to /v1/events/stream for live updates (it picks
 * out the {@code base_found} type itself).
 */
fun Route.basesRoutes(repo: BotEventRepository) {
    get("/v1/bases") {
        val dim = call.request.queryParameters["dim"]
        val minScore = call.request.queryParameters["min_score"]?.toDoubleOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 1000
        val bases = repo.bases(dimension = dim, minScore = minScore, limit = limit)
        call.respond(BasesResponse(total = bases.size, bases = bases))
    }
}
