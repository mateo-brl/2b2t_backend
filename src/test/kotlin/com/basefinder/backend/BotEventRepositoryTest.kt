package com.basefinder.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests against an in-memory SQLite. Uses {@code mode=memory} +
 * {@code cache=shared} so the connection pool sees a single DB instance.
 */
class BotEventRepositoryTest {

    private lateinit var repo: BotEventRepository

    private fun obj(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    @BeforeTest
    fun setup() {
        // Unique URL per test → each test gets its own in-memory DB.
        val jdbcUrl = "jdbc:sqlite:file:test-${System.nanoTime()}?mode=memory&cache=shared"
        DatabaseFactory.init(jdbcUrl)
        repo = BotEventRepository()
    }

    @AfterTest
    fun tearDown() {
        transaction { SchemaUtils.drop(BotEventsTable) }
    }

    @Test
    fun ingestStoresAndCounts() {
        repo.ingest(obj("""{"type":"bot_tick","seq":1,"ts_utc_ms":100,"idempotency_key":"tick:1"}"""))
        repo.ingest(obj("""{"type":"base_found","seq":2,"ts_utc_ms":200,"idempotency_key":"base:1"}"""))

        assertEquals(2L, repo.count())
        assertEquals(2L, repo.totalReceived())
    }

    @Test
    fun duplicateKeyIsDeduped() {
        val a = repo.ingest(obj("""{"type":"bot_tick","seq":1,"ts_utc_ms":100,"idempotency_key":"tick:1"}"""))
        val b = repo.ingest(obj("""{"type":"bot_tick","seq":1,"ts_utc_ms":100,"idempotency_key":"tick:1"}"""))

        assertEquals(BotEventRepository.InsertOutcome.INSERTED, a)
        assertEquals(BotEventRepository.InsertOutcome.DUPLICATE, b)
        assertEquals(1L, repo.count())
        assertEquals(2L, repo.totalReceived())
    }

    @Test
    fun missingIdempotencyKeyIsMalformed() {
        val outcome = repo.ingest(obj("""{"type":"bot_tick","seq":1,"ts_utc_ms":100}"""))
        assertEquals(BotEventRepository.InsertOutcome.MALFORMED, outcome)
        assertEquals(0L, repo.count())
    }

    @Test
    fun missingRequiredFieldIsMalformed() {
        val outcome = repo.ingest(obj("""{"idempotency_key":"x"}"""))
        assertEquals(BotEventRepository.InsertOutcome.MALFORMED, outcome)
    }

    @Test
    fun recentReturnsInsertOrder() {
        repeat(5) { i ->
            repo.ingest(obj("""{"type":"bot_tick","seq":$i,"ts_utc_ms":${i * 100},"idempotency_key":"tick:$i"}"""))
        }
        val recent = repo.recent(3)
        assertEquals(3, recent.size)
        // Last 3 by id ASC after reverse: seq 2, 3, 4
        assertEquals("2", recent[0]["seq"].toString())
        assertEquals("4", recent[2]["seq"].toString())
    }

    @Test
    fun recentReturnsAllWhenLimitExceeds() {
        repeat(3) { i ->
            repo.ingest(obj("""{"type":"bot_tick","seq":$i,"ts_utc_ms":${i * 100},"idempotency_key":"tick:$i"}"""))
        }
        assertEquals(3, repo.recent(100).size)
    }

    @Test
    fun payloadIsRoundTripped() {
        repo.ingest(obj("""{"type":"base_found","seq":1,"ts_utc_ms":100,"idempotency_key":"b:1","chunk_x":42,"score":99.5}"""))
        val recent = repo.recent(1)
        assertEquals(1, recent.size)
        val event = recent[0]
        assertEquals("base_found", event["type"]!!.toString().trim('"'))
        assertEquals("42", event["chunk_x"].toString())
        assertTrue(event.toString().contains("99.5"))
    }
}
