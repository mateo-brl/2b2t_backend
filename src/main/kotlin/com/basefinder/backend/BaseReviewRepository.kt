package com.basefinder.backend

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class BaseReviewRepository {

    enum class Status { PENDING, REAL, FALSE_POSITIVE, INTERESTING }

    data class Review(
        val baseKey: String,
        val status: Status,
        val notes: String?,
        val reviewedAt: Long,
    )

    fun upsert(baseKey: String, status: Status, notes: String?): Review = transaction {
        val now = System.currentTimeMillis()
        val existing = BaseReviewsTable
            .selectAll()
            .where { BaseReviewsTable.baseKey eq baseKey }
            .firstOrNull()
        if (existing != null) {
            BaseReviewsTable.update({ BaseReviewsTable.baseKey eq baseKey }) {
                it[BaseReviewsTable.status] = status.name
                it[BaseReviewsTable.notes] = notes
                it[BaseReviewsTable.reviewedAt] = now
            }
        } else {
            BaseReviewsTable.insert {
                it[BaseReviewsTable.baseKey] = baseKey
                it[BaseReviewsTable.status] = status.name
                it[BaseReviewsTable.notes] = notes
                it[BaseReviewsTable.reviewedAt] = now
            }
        }
        Review(baseKey, status, notes, now)
    }

    fun get(baseKey: String): Review? = transaction {
        BaseReviewsTable
            .selectAll()
            .where { BaseReviewsTable.baseKey eq baseKey }
            .firstOrNull()
            ?.let(::rowToReview)
    }

    fun listByStatus(status: Status, limit: Int = 100): List<Review> = transaction {
        BaseReviewsTable
            .selectAll()
            .where { BaseReviewsTable.status eq status.name }
            .orderBy(BaseReviewsTable.reviewedAt, SortOrder.DESC)
            .limit(limit)
            .map(::rowToReview)
    }

    fun count(): Map<Status, Long> = transaction {
        val out = mutableMapOf<Status, Long>()
        for (s in Status.entries) out[s] = 0L
        for (row in BaseReviewsTable.selectAll()) {
            try {
                val s = Status.valueOf(row[BaseReviewsTable.status])
                out[s] = (out[s] ?: 0L) + 1L
            } catch (_: IllegalArgumentException) { }
        }
        out
    }

    fun delete(baseKey: String): Boolean = transaction {
        BaseReviewsTable.deleteWhere { it.run { BaseReviewsTable.baseKey eq baseKey } } > 0
    }

    private fun rowToReview(row: ResultRow): Review {
        val rawStatus = row[BaseReviewsTable.status]
        val status = try { Status.valueOf(rawStatus) } catch (e: IllegalArgumentException) { Status.PENDING }
        return Review(
            baseKey = row[BaseReviewsTable.baseKey],
            status = status,
            notes = row[BaseReviewsTable.notes],
            reviewedAt = row[BaseReviewsTable.reviewedAt],
        )
    }
}
