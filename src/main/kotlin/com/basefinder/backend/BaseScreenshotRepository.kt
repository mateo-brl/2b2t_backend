package com.basefinder.backend

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class BaseScreenshotRepository {

    data class Screenshot(
        val id: Long,
        val baseKey: String,
        val angle: String,
        val filePath: String,
        val takenAtMs: Long,
        val receivedAt: Long,
    )

    /**
     * Upsert : une nouvelle entrée pour (baseKey, angle) écrase l'ancienne
     * (le fichier est ré-écrit côté FS, la ligne est mise à jour).
     */
    fun upsert(baseKey: String, angle: String, filePath: String, takenAtMs: Long): Long = transaction {
        val now = System.currentTimeMillis()
        val existing = BaseScreenshotsTable
            .selectAll()
            .where {
                (BaseScreenshotsTable.baseKey eq baseKey)
                    .and(BaseScreenshotsTable.angle eq angle)
            }
            .firstOrNull()
        if (existing != null) {
            BaseScreenshotsTable.update({
                (BaseScreenshotsTable.baseKey eq baseKey)
                    .and(BaseScreenshotsTable.angle eq angle)
            }) {
                it[BaseScreenshotsTable.filePath] = filePath
                it[BaseScreenshotsTable.takenAtMs] = takenAtMs
                it[BaseScreenshotsTable.receivedAt] = now
            }
            existing[BaseScreenshotsTable.id].value
        } else {
            BaseScreenshotsTable.insert {
                it[BaseScreenshotsTable.baseKey] = baseKey
                it[BaseScreenshotsTable.angle] = angle
                it[BaseScreenshotsTable.filePath] = filePath
                it[BaseScreenshotsTable.takenAtMs] = takenAtMs
                it[BaseScreenshotsTable.receivedAt] = now
            }[BaseScreenshotsTable.id].value
        }
    }

    fun listForBase(baseKey: String): List<Screenshot> = transaction {
        BaseScreenshotsTable
            .selectAll()
            .where { BaseScreenshotsTable.baseKey eq baseKey }
            .orderBy(BaseScreenshotsTable.receivedAt, SortOrder.ASC)
            .map(::rowToScreenshot)
    }

    fun get(id: Long): Screenshot? = transaction {
        BaseScreenshotsTable
            .selectAll()
            .where { BaseScreenshotsTable.id eq id }
            .firstOrNull()
            ?.let(::rowToScreenshot)
    }

    fun delete(id: Long): Boolean = transaction {
        BaseScreenshotsTable.deleteWhere { it.run { BaseScreenshotsTable.id eq id } } > 0
    }

    private fun rowToScreenshot(row: ResultRow) = Screenshot(
        id = row[BaseScreenshotsTable.id].value,
        baseKey = row[BaseScreenshotsTable.baseKey],
        angle = row[BaseScreenshotsTable.angle],
        filePath = row[BaseScreenshotsTable.filePath],
        takenAtMs = row[BaseScreenshotsTable.takenAtMs],
        receivedAt = row[BaseScreenshotsTable.receivedAt],
    )
}

/** Tiny extension to keep the where { (a) and (b) } chain readable. */
private infix fun org.jetbrains.exposed.sql.Op<Boolean>.and(
    other: org.jetbrains.exposed.sql.Op<Boolean>,
): org.jetbrains.exposed.sql.Op<Boolean> = org.jetbrains.exposed.sql.AndOp(listOf(this, other))
