package com.basefinder.backend

import org.jetbrains.exposed.sql.Table

/**
 * Schéma {@code base_reviews}.
 *
 * Une ligne par base reviewée (clé naturelle = idempotency_key de la
 * base). PK composite avec un seul champ pour faire un upsert simple.
 *
 * {@code status} ∈ {PENDING, REAL, FALSE_POSITIVE, INTERESTING}.
 * Une base sans ligne ici est implicitement PENDING.
 */
object BaseReviewsTable : Table("base_reviews") {
    val baseKey = varchar("base_key", 128)
    val status = varchar("status", 32)
    val notes = text("notes").nullable()
    val reviewedAt = long("reviewed_at")

    override val primaryKey = PrimaryKey(baseKey, name = "pk_base_reviews")
}
