package com.basefinder.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class CoverageCellDto(val cx: Int, val cz: Int, val count: Int)

@Serializable
data class CoverageResponse(
    val dim: String,
    val grid: Int,
    val cellSizeBlocks: Int,
    val xMin: Int,
    val xMax: Int,
    val zMin: Int,
    val zMax: Int,
    val cells: List<CoverageCellDto>,
    val total: Long,
)

/**
 * GET /v1/coverage?dim=overworld&xmin=&xmax=&zmin=&zmax=&grid=64
 *
 * Renvoie les cellules d'une grille agrégée des chunks scannés dans la bbox
 * demandée. Le client choisit {@code grid} (côté de cellule en chunks) selon
 * son zoom : zoom out → grosses cellules ; zoom in → petites cellules.
 */
fun Route.coverageRoutes(repo: BotEventRepository) {
    get("/v1/coverage") {
        val dim = call.request.queryParameters["dim"] ?: "overworld"
        val grid = (call.request.queryParameters["grid"]?.toIntOrNull() ?: 64).coerceIn(1, 4096)
        val xmin = call.request.queryParameters["xmin"]?.toIntOrNull() ?: -30_000_000
        val xmax = call.request.queryParameters["xmax"]?.toIntOrNull() ?: 30_000_000
        val zmin = call.request.queryParameters["zmin"]?.toIntOrNull() ?: -30_000_000
        val zmax = call.request.queryParameters["zmax"]?.toIntOrNull() ?: 30_000_000
        if (xmin >= xmax || zmin >= zmax) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid bbox"))
            return@get
        }
        val cells = repo.coverage(dim, grid, xmin, xmax, zmin, zmax)
            .map { CoverageCellDto(it.cx, it.cz, it.count) }
        call.respond(
            CoverageResponse(
                dim = dim,
                grid = grid,
                cellSizeBlocks = grid * 16,
                xMin = xmin, xMax = xmax,
                zMin = zmin, zMax = zmax,
                cells = cells,
                total = repo.coverageCount(dim),
            ),
        )
    }
}
