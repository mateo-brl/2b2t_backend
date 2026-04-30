package com.basefinder.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File

private val basesLog = LoggerFactory.getLogger("BasesRoutes")

@Serializable
data class BasesResponse(val total: Int, val bases: List<JsonObject>)

/**
 * Bases-only read API for the dashboard map. Filters server-side by
 * dimension and minimum score. The dashboard pulls the initial set on
 * mount and listens to /v1/events/stream for live updates (it picks
 * out the {@code base_found} type itself).
 */
fun Route.basesRoutes(
    repo: BotEventRepository,
    screenshotRepo: BaseScreenshotRepository = BaseScreenshotRepository(),
    reviewRepo: BaseReviewRepository = BaseReviewRepository(),
) {
    get("/v1/bases") {
        val dim = call.request.queryParameters["dim"]
        val minScore = call.request.queryParameters["min_score"]?.toDoubleOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 1000
        val bases = repo.bases(dimension = dim, minScore = minScore, limit = limit)
        call.respond(BasesResponse(total = bases.size, bases = bases))
    }

    /**
     * Supprime une base par sa clé d'idempotence (encodée dans l'URL).
     * Cascade : on supprime aussi les screenshots associées (rows + fichiers
     * sur disque) et la review row éventuelle. Sans le cascade,
     * d'anciennes screenshots et reviews orphelines pollueraient les
     * panels du dashboard et ne pourraient plus être nettoyées via l'UI.
     *
     * Idempotent : si la base n'existe que via screenshots/review (cas
     * "orphan" déjà nettoyé côté events), on cascade quand même les
     * artefacts restants et on répond 204.
     */
    delete("/v1/bases/{key}") {
        val key = call.parameters["key"] ?: ""
        if (key.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing key"))
            return@delete
        }

        // Cascade screenshots (files on disk + rows)
        val shots = screenshotRepo.listForBase(key)
        for (s in shots) {
            try {
                File(s.filePath).delete()
            } catch (e: Throwable) {
                basesLog.warn("Failed to delete screenshot file {}: {}", s.filePath, e.message)
            }
            screenshotRepo.delete(s.id)
        }
        // Try to drop the (likely empty) per-base directory too.
        if (shots.isNotEmpty()) {
            val parent = File(shots.first().filePath).parentFile
            if (parent?.isDirectory == true && (parent.list()?.isEmpty() == true)) {
                parent.delete()
            }
        }

        // Cascade review
        reviewRepo.delete(key)

        // Drop the event row itself
        val deletedEvent = repo.deleteByKey(key)

        if (deletedEvent || shots.isNotEmpty()) {
            basesLog.info("Cascade-deleted {} (event={}, screenshots={})",
                key, deletedEvent, shots.size)
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
