package com.basefinder.backend

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class SearchZoneRepository {

    data class Zone(
        val id: Long,
        val name: String,
        val dim: String,
        val shape: String,
        val geometry: String,
        val active: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
    )

    fun create(name: String, dim: String, shape: String, geometry: String, active: Boolean = true): Long = transaction {
        val now = System.currentTimeMillis()
        SearchZonesTable.insert {
            it[SearchZonesTable.name] = name
            it[SearchZonesTable.dim] = dim
            it[SearchZonesTable.shape] = shape
            it[SearchZonesTable.geometry] = geometry
            it[SearchZonesTable.active] = active
            it[SearchZonesTable.createdAt] = now
            it[SearchZonesTable.updatedAt] = now
        }[SearchZonesTable.id].value
    }

    fun list(dim: String? = null, activeOnly: Boolean = false): List<Zone> = transaction {
        var q = SearchZonesTable.selectAll().orderBy(SearchZonesTable.id, SortOrder.ASC)
        if (dim != null) q = q.andWhere { SearchZonesTable.dim eq dim }
        if (activeOnly) q = q.andWhere { SearchZonesTable.active eq true }
        q.map(::rowToZone)
    }

    fun get(id: Long): Zone? = transaction {
        SearchZonesTable.selectAll().where { SearchZonesTable.id eq id }.firstOrNull()?.let(::rowToZone)
    }

    fun update(
        id: Long,
        name: String?,
        geometry: String?,
        shape: String?,
        active: Boolean?,
    ): Boolean = transaction {
        val updated = SearchZonesTable.update({ SearchZonesTable.id eq id }) {
            if (name != null) it[SearchZonesTable.name] = name
            if (geometry != null) it[SearchZonesTable.geometry] = geometry
            if (shape != null) it[SearchZonesTable.shape] = shape
            if (active != null) it[SearchZonesTable.active] = active
            it[SearchZonesTable.updatedAt] = System.currentTimeMillis()
        }
        updated > 0
    }

    fun delete(id: Long): Boolean = transaction {
        // deleteWhere lambda is `Table.(ISqlExpressionBuilder) -> Op<Boolean>`
        // (Exposed 0.56) — `this` is the table, the builder is the parameter.
        // We need it.run { ... } to bring `eq` into scope.
        SearchZonesTable.deleteWhere { it.run { SearchZonesTable.id eq id } } > 0
    }

    private fun rowToZone(row: ResultRow) = Zone(
        id = row[SearchZonesTable.id].value,
        name = row[SearchZonesTable.name],
        dim = row[SearchZonesTable.dim],
        shape = row[SearchZonesTable.shape],
        geometry = row[SearchZonesTable.geometry],
        active = row[SearchZonesTable.active],
        createdAt = row[SearchZonesTable.createdAt],
        updatedAt = row[SearchZonesTable.updatedAt],
    )
}
