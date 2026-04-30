package com.basefinder.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class ReviewBody(val status: String, val notes: String? = null)

@Serializable
data class ReviewDto(
    val baseKey: String,
    val status: String,
    val notes: String? = null,
    val reviewedAt: Long,
)

@Serializable
data class ReviewsListResponse(val total: Int, val reviews: List<ReviewDto>)

@Serializable
data class ReviewCountsResponse(
    val pending: Long,
    val real: Long,
    val falsePositive: Long,
    val interesting: Long,
)

private fun toDto(r: BaseReviewRepository.Review): ReviewDto = ReviewDto(
    baseKey = r.baseKey,
    status = r.status.name,
    notes = r.notes,
    reviewedAt = r.reviewedAt,
)

/**
 * - {@code POST /v1/bases/{key}/review} — body
 *   {@code {status, notes?}}, status ∈ {PENDING, REAL, FALSE_POSITIVE,
 *   INTERESTING}. Upsert.
 * - {@code GET /v1/bases/{key}/review} — récupère review (404 si pas reviewée).
 * - {@code GET /v1/reviews?status=...&limit=N} — liste les reviews
 *   d'un statut donné.
 * - {@code GET /v1/reviews/counts} — compteur par statut (pour la
 *   sidebar "Review · 12 pending").
 */
fun Route.reviewsRoutes(repo: BaseReviewRepository) {
    post("/v1/bases/{key}/review") {
        val key = call.parameters["key"] ?: ""
        if (key.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing key"))
            return@post
        }
        val body = call.receive<ReviewBody>()
        val status = try {
            BaseReviewRepository.Status.valueOf(body.status.uppercase())
        } catch (e: IllegalArgumentException) {
            val allowed = BaseReviewRepository.Status.entries.joinToString(",") { it.name }
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "invalid status", "allowed" to allowed),
            )
            return@post
        }
        val saved = repo.upsert(key, status, body.notes?.takeIf { it.isNotBlank() })
        call.respond(toDto(saved))
    }

    get("/v1/bases/{key}/review") {
        val key = call.parameters["key"] ?: ""
        if (key.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing key"))
            return@get
        }
        val r = repo.get(key)
        if (r == null) call.respond(HttpStatusCode.NotFound)
        else call.respond(toDto(r))
    }

    get("/v1/reviews") {
        val statusParam = call.request.queryParameters["status"]?.uppercase() ?: "PENDING"
        val status = try {
            BaseReviewRepository.Status.valueOf(statusParam)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid status"))
            return@get
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        val reviews = repo.listByStatus(status, limit).map(::toDto)
        call.respond(ReviewsListResponse(reviews.size, reviews))
    }

    get("/v1/reviews/counts") {
        val map = repo.count()
        call.respond(ReviewCountsResponse(
            pending = map[BaseReviewRepository.Status.PENDING] ?: 0,
            real = map[BaseReviewRepository.Status.REAL] ?: 0,
            falsePositive = map[BaseReviewRepository.Status.FALSE_POSITIVE] ?: 0,
            interesting = map[BaseReviewRepository.Status.INTERESTING] ?: 0,
        ))
    }

    delete("/v1/bases/{key}/review") {
        val key = call.parameters["key"] ?: ""
        if (key.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing key"))
            return@delete
        }
        if (repo.delete(key)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound)
    }
}
