package com.basefinder.backend

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class BotCommandRepository {

    data class Command(
        val id: Long,
        val type: String,
        val payload: String,
        val createdAt: Long,
        val ackAt: Long?,
    )

    fun create(type: String, payload: String): Long = transaction {
        BotCommandsTable.insert {
            it[BotCommandsTable.type] = type
            it[BotCommandsTable.payload] = payload
            it[BotCommandsTable.createdAt] = System.currentTimeMillis()
            it[BotCommandsTable.ackAt] = null
        }[BotCommandsTable.id].value
    }

    fun list(unackOnly: Boolean = false, limit: Int = 100): List<Command> = transaction {
        var q = BotCommandsTable
            .selectAll()
            .orderBy(BotCommandsTable.id, SortOrder.ASC)
            .limit(limit.coerceIn(1, 1000))
        if (unackOnly) q = q.andWhere { BotCommandsTable.ackAt.isNull() }
        q.map(::rowToCommand)
    }

    fun get(id: Long): Command? = transaction {
        BotCommandsTable.selectAll()
            .where { BotCommandsTable.id eq id }
            .firstOrNull()?.let(::rowToCommand)
    }

    fun ack(id: Long): Boolean = transaction {
        BotCommandsTable.update({ BotCommandsTable.id eq id }) {
            it[BotCommandsTable.ackAt] = System.currentTimeMillis()
        } > 0
    }

    fun delete(id: Long): Boolean = transaction {
        BotCommandsTable.deleteWhere { it.run { BotCommandsTable.id eq id } } > 0
    }

    private fun rowToCommand(row: org.jetbrains.exposed.sql.ResultRow) = Command(
        id = row[BotCommandsTable.id].value,
        type = row[BotCommandsTable.type],
        payload = row[BotCommandsTable.payload],
        createdAt = row[BotCommandsTable.createdAt],
        ackAt = row[BotCommandsTable.ackAt],
    )
}
