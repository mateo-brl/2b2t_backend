package com.basefinder.backend

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Schéma {@code base_screenshots}.
 *
 * Une ligne par capture (3 par base : aerial / ground / detail). Le
 * fichier PNG vit sur disque, on stocke seulement le chemin relatif
 * + métadonnées. {@code (base_idempotency_key, angle)} est unique :
 * un nouveau upload pour la même clé + angle écrase le fichier
 * existant et bump {@code received_at}.
 */
object BaseScreenshotsTable : LongIdTable("base_screenshots") {
    val baseKey = varchar("base_key", 128).index()
    val angle = varchar("angle", 32)
    val filePath = text("file_path")
    val takenAtMs = long("taken_at_ms")
    val receivedAt = long("received_at").index()

    init {
        uniqueIndex("uk_base_screenshots_key_angle", baseKey, angle)
    }
}
