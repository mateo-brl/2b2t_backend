package com.basefinder.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * SQLite database setup. Single-writer dialect, but our throughput target
 * is ~10 events/s/bot, well under the SQLite ceiling. Switch to Postgres
 * by replacing the JDBC URL once the VPS is up — Exposed schema is
 * dialect-agnostic.
 *
 * File path defaults to {@code data/basefinder.db} relative to the working
 * directory; override with env {@code DB_PATH}.
 */
object DatabaseFactory {
    fun init(jdbcUrl: String) {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            // SQLite is single-writer; Hikari pool size > 1 just wastes connections.
            maximumPoolSize = if (jdbcUrl.startsWith("jdbc:sqlite")) 1 else 8
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
        }
        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)
        transaction {
            SchemaUtils.create(
                BotEventsTable,
                ScannedChunksTable,
                SearchZonesTable,
                BotCommandsTable,
                BaseScreenshotsTable,
                BaseReviewsTable,
            )
        }
    }

    fun defaultJdbcUrl(): String {
        val path = System.getenv("DB_PATH") ?: "data/basefinder.db"
        java.io.File(path).parentFile?.mkdirs()
        return "jdbc:sqlite:$path"
    }
}
