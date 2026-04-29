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
    val eventsStored: Int
)

fun Route.healthRoutes() {
    get("/v1/health") {
        call.respond(
            HealthResponse(
                status = "ok",
                version = "0.1.0",
                eventsReceived = EventStore.shared.totalReceived(),
                eventsStored = EventStore.shared.stored()
            )
        )
    }
}
