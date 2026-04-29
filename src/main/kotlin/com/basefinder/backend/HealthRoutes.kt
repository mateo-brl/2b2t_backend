package com.basefinder.backend

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val eventsReceived: Long,
    val eventsStored: Long
)

fun Route.healthRoutes(repo: BotEventRepository) {
    get("/v1/health") {
        call.respond(
            HealthResponse(
                status = "ok",
                version = "0.2.0",
                eventsReceived = repo.totalReceived(),
                eventsStored = repo.count()
            )
        )
    }
}
