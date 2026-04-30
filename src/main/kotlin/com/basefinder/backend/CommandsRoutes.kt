package com.basefinder.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class CommandCreateBody(
    val type: String,
    val payload: JsonObject? = null,
)

@Serializable
data class CommandDto(
    val id: Long,
    val type: String,
    val payload: JsonObject,
    val createdAt: Long,
    val ackAt: Long? = null,
)

@Serializable
data class CommandListResponse(val total: Int, val commands: List<CommandDto>)

private val commandParser = Json { ignoreUnknownKeys = true }

private fun toDto(c: BotCommandRepository.Command): CommandDto = CommandDto(
    id = c.id,
    type = c.type,
    payload = commandParser.parseToJsonElement(
        if (c.payload.isBlank()) "{}" else c.payload,
    ) as JsonObject,
    createdAt = c.createdAt,
    ackAt = c.ackAt,
)

/**
 * Files de commandes du dashboard vers le bot. Le bot poll
 * `GET /v1/commands?ack=false` toutes les 2 s ; le dashboard POSTe
 * de nouvelles commandes ; le bot ACK une fois exécutée.
 *
 * Types prévus :
 * - {@code basefinder.toggle}    — bascule BaseHunter ON/OFF.
 * - {@code basefinder.enable}    — force ON (no-op si déjà ON).
 * - {@code basefinder.disable}   — force OFF (no-op si déjà OFF).
 * - {@code basefinder.pause}     — pause le module.
 * - {@code basefinder.resume}    — reprend.
 * - {@code basefinder.skip}      — skipWaypoint().
 *
 * Le bot tolère silencieusement un type inconnu (logue et ack pour ne
 * pas bloquer la queue).
 */
fun Route.commandsRoutes(repo: BotCommandRepository) {
    post("/v1/commands") {
        val body = call.receive<CommandCreateBody>()
        if (body.type.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing type"))
            return@post
        }
        val payloadStr = body.payload?.toString() ?: "{}"
        val id = repo.create(body.type, payloadStr)
        val c = repo.get(id) ?: error("command $id disappeared after insert")
        call.respond(HttpStatusCode.Created, toDto(c))
    }

    get("/v1/commands") {
        val unack = call.request.queryParameters["ack"]?.equals("false", true) == true
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        val cmds = repo.list(unack, limit).map(::toDto)
        call.respond(CommandListResponse(cmds.size, cmds))
    }

    post("/v1/commands/{id}/ack") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@post
        }
        if (repo.ack(id)) {
            val c = repo.get(id) ?: error("ack'd command $id missing")
            call.respond(toDto(c))
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    delete("/v1/commands/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@delete
        }
        if (repo.delete(id)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound)
    }
}
