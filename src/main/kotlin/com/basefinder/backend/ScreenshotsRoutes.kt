package com.basefinder.backend

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

private val log = LoggerFactory.getLogger("ScreenshotsRoutes")

@Serializable
data class ScreenshotDto(
    val id: Long,
    val baseKey: String,
    val angle: String,
    val takenAtMs: Long,
    val receivedAt: Long,
    val url: String,
)

@Serializable
data class ScreenshotsResponse(val total: Int, val screenshots: List<ScreenshotDto>)

private fun toDto(s: BaseScreenshotRepository.Screenshot): ScreenshotDto = ScreenshotDto(
    id = s.id,
    baseKey = s.baseKey,
    angle = s.angle,
    takenAtMs = s.takenAtMs,
    receivedAt = s.receivedAt,
    url = "/v1/screenshots/${s.id}/raw",
)

/**
 * Sanitize une chaîne libre arrivant du bot pour qu'elle puisse servir
 * de segment de chemin sur le FS du backend. Garde lettres / chiffres /
 * point / underscore / tiret. Le reste devient "_". Long max 200.
 */
private fun safePathSegment(s: String): String {
    val cleaned = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return if (cleaned.length > 200) cleaned.substring(0, 200) else cleaned
}

/**
 * - {@code POST /v1/screenshots} (multipart) — fichier {@code file} +
 *   form fields {@code base_key}, {@code angle}, {@code taken_at_ms}.
 *   Stocké dans {@code <storageRoot>/<safe_base_key>/<safe_angle>.png}.
 * - {@code GET /v1/screenshots?base_key=...} — liste.
 * - {@code GET /v1/screenshots/{id}/raw} — sert le PNG.
 * - {@code DELETE /v1/screenshots/{id}} — supprime ligne + fichier.
 */
fun Route.screenshotsRoutes(
    repo: BaseScreenshotRepository,
    storageRoot: Path,
) {
    post("/v1/screenshots") {
        var baseKey: String? = null
        var angle: String? = null
        var takenAtMs: Long? = null
        var savedFile: File? = null

        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "base_key" -> baseKey = part.value
                        "angle" -> angle = part.value
                        "taken_at_ms" -> takenAtMs = part.value.toLongOrNull()
                        else -> {}
                    }
                    is PartData.FileItem -> {
                        // We can't write yet — we may not have base_key /
                        // angle. Buffer to a temp file, then rename below.
                        val tmp = File.createTempFile("upload_", ".png")
                        part.provider().toInputStream().use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                        savedFile = tmp
                    }
                    else -> {}
                }
            } finally {
                part.dispose()
            }
        }

        val key = baseKey
        val ang = angle
        val taken = takenAtMs
        val file = savedFile
        if (key.isNullOrBlank() || ang.isNullOrBlank() || taken == null || file == null) {
            file?.delete()
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "missing fields",
                "required" to listOf("base_key", "angle", "taken_at_ms", "file"),
            ))
            return@post
        }

        val targetDir = storageRoot.resolve(safePathSegment(key)).toFile()
        targetDir.mkdirs()
        val target = File(targetDir, safePathSegment(ang) + ".png")
        if (target.exists()) target.delete()
        if (!file.renameTo(target)) {
            // Cross-FS rename can fail — fallback to copy.
            file.inputStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            file.delete()
        }

        val id = repo.upsert(key, ang, target.absolutePath, taken)
        val saved = repo.get(id) ?: error("screenshot $id missing after insert")
        log.info("Screenshot stored: base_key={} angle={} bytes={} path={}", key, ang, target.length(), target.absolutePath)
        call.respond(HttpStatusCode.Created, toDto(saved))
    }

    get("/v1/screenshots") {
        val baseKey = call.request.queryParameters["base_key"]
        if (baseKey.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing base_key"))
            return@get
        }
        val list = repo.listForBase(baseKey).map(::toDto)
        call.respond(ScreenshotsResponse(list.size, list))
    }

    get("/v1/screenshots/{id}/raw") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@get
        }
        val s = repo.get(id)
        if (s == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val file = File(s.filePath)
        if (!file.exists() || !file.isFile) {
            log.warn("Row {} points at missing file {}", id, s.filePath)
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.headers.append(
            io.ktor.http.HttpHeaders.CacheControl,
            "public, max-age=86400",
        )
        call.response.headers.append(
            io.ktor.http.HttpHeaders.ContentDisposition,
            ContentDisposition.Inline
                .withParameter(ContentDisposition.Parameters.FileName, "${s.baseKey}_${s.angle}.png")
                .toString(),
        )
        call.respondFile(file)
    }

    delete("/v1/screenshots/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            return@delete
        }
        val s = repo.get(id)
        if (s == null) {
            call.respond(HttpStatusCode.NotFound)
            return@delete
        }
        File(s.filePath).delete()
        repo.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }
}
