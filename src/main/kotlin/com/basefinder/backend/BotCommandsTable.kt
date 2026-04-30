package com.basefinder.backend

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Schéma {@code bot_commands}.
 *
 * File de commandes destinées au bot, créées depuis le dashboard
 * (toggle BaseHunter, pause, resume, skip waypoint, ...). Le bot poll
 * `GET /v1/commands?ack=false` toutes les 2 s, exécute les commandes
 * non-acquittées, et POST `/v1/commands/{id}/ack` pour marquer terminé.
 *
 * `ack_at NULL` = pending. `ack_at` rempli = exécutée. On garde l'historique
 * pour debug + UI ("dernière action effectuée").
 */
object BotCommandsTable : LongIdTable("bot_commands") {
    val type = varchar("type", 64).index()
    val payload = text("payload")
    val createdAt = long("created_at").index()
    val ackAt = long("ack_at").nullable()
}
