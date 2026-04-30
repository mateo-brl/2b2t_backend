package com.basefinder.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ZoneCreateBody(
    val name: String? = null,
    val dim: String,
    val shape: String,
    val geometry: JsonObject,
    val active: Boolean? = null,
)

@Serializable
data class ZoneUpdateBody(
    val name: String? = null,
    val shape: String? = null,
    val geometry: JsonObject? = null,
    val active: Boolean? = null,
)

@Serializable
data class ZoneDto(
    val id: Long,
    val name: String,
    val dim: String,
    val shape: String,
    val geometry: JsonObject,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ZonesListResponse(val total: Int, val zones: List<ZoneDto>)

private val parser = Json { ignoreUnknownKeys = true }

private fun toDto(z: SearchZoneRepository.Zone): ZoneDto = ZoneDto(
    id = z.id,
    name = z.name,
    dim = z.dim,
    shape = z.shape,
    geometry = parser.parseToJsonElement(z.geometry) as JsonObject,
    active = z.active,
    createdAt = z.createdAt,
    updatedAt = z.updatedAt,
)

/**
 * CRUD pour les zones de recherche dessinées dans le dashboard.
 *
 * - {@code POST /v1/zones}           crée une zone (body : ZoneCreateBody).
 * - {@code GET /v1/zones?dim=&active=true} liste filtré.
 * - {@code GET /v1/zones/{id}}       détail.
 * - {@code PUT /v1/zones/{id}}       update (champs optionnels).
 * - {@code DELETE /v1/zones/{id}}    supprime.
 *
 * La géométrie est stockée verbatim en JSON (Geoman/GeoJSON) et passée
 * verbatim au bot ; pas de validation côté serveur pour cette MVP.
 */
fun Route.zonesRoutes(repo: SearchZoneRepository) {
    post("/v1/zones") {
        val body = call.receive<ZoneCreateBody>()
        val id = repo.create(
            name = body.name?.takeIf { it.isNotBlank() } ?: "zone-${System.currentTimeMillis()}",
            dim = body.dim,
            shape = body.shape,
            geometry = body.geometry.toString(),
            active = body.active ?: true,
        )
        val z = repo.get(id) ?: error("zone $id disappeared after insert")
        call.respond(HttpStatusCode.Created, toDto(z))
    }

    get("/v1/zones") {
        val dim = call.request.queryParameters["dim"]
        val activeOnly = call.request.queryParameters["active"]?.equals("true", ignoreCase = true) == true
        val zones = repo.list(dim, activeOnly).map(::toDto)
        call.respond(ZonesListResponse(zones.size, zones))
    }

    get("/v1/zones/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@get
        }
        val z = repo.get(id)
        if (z == null) call.respond(HttpStatusCode.NotFound)
        else call.respond(toDto(z))
    }

    put("/v1/zones/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@put
        }
        val body = call.receive<ZoneUpdateBody>()
        val ok = repo.update(
            id = id,
            name = body.name,
            geometry = body.geometry?.toString(),
            shape = body.shape,
            active = body.active,
        )
        if (!ok) {
            call.respond(HttpStatusCode.NotFound)
            return@put
        }
        val z = repo.get(id) ?: error("zone $id disappeared after update")
        call.respond(toDto(z))
    }

    delete("/v1/zones/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@delete
        }
        if (repo.delete(id)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound)
    }
}
