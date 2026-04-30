package com.basefinder.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private val rootLog = LoggerFactory.getLogger("Application")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val jdbcUrl = System.getenv("JDBC_URL") ?: DatabaseFactory.defaultJdbcUrl()
    val storageRoot = Path.of(System.getenv("SCREENSHOT_DIR") ?: "data/screenshots")
    Files.createDirectories(storageRoot)
    DatabaseFactory.init(jdbcUrl)
    rootLog.info("Database initialised at {}", jdbcUrl)
    rootLog.info("Screenshot storage at {}", storageRoot.toAbsolutePath())
    val broadcaster = EventBroadcaster()
    val repo = BotEventRepository(broadcaster)
    val zoneRepo = SearchZoneRepository()
    val commandRepo = BotCommandRepository()
    val screenshotRepo = BaseScreenshotRepository()
    val reviewRepo = BaseReviewRepository()
    embeddedServer(Netty, port = port, host = host) {
        module(repo, broadcaster, zoneRepo, commandRepo, screenshotRepo, reviewRepo, storageRoot)
    }.start(wait = true)
}

fun Application.module(
    repo: BotEventRepository,
    broadcaster: EventBroadcaster,
    zoneRepo: SearchZoneRepository = SearchZoneRepository(),
    commandRepo: BotCommandRepository = BotCommandRepository(),
    screenshotRepo: BaseScreenshotRepository = BaseScreenshotRepository(),
    reviewRepo: BaseReviewRepository = BaseReviewRepository(),
    screenshotStorageRoot: Path = Path.of("data/screenshots"),
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
        })
    }
    install(CORS) {
        // Dev: Vite default ports. Will be replaced by an allowlist of
        // production origins (and Discord OAuth callback) in Phase 4.
        allowHost("localhost:5173")
        allowHost("127.0.0.1:5173")
        allowHost("localhost:4173") // vite preview
        allowHost("127.0.0.1:4173")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.CacheControl)
    }
    install(SSE)
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            rootLog.error("Unhandled error", cause)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal")))
        }
    }
    routing {
        healthRoutes(repo)
        ingestRoutes(repo)
        basesRoutes(repo)
        coverageRoutes(repo)
        zonesRoutes(zoneRepo)
        commandsRoutes(commandRepo)
        screenshotsRoutes(screenshotRepo, screenshotStorageRoot)
        reviewsRoutes(reviewRepo)
        sseRoutes(broadcaster)
    }
}
