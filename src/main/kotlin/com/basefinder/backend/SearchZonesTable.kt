package com.basefinder.backend

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Schéma {@code search_zones}.
 *
 * Une ligne = une zone dessinée par l'utilisateur depuis le dashboard
 * (rectangle, polygon, cercle, freehand). Le bot poll cette table pour
 * restreindre son scan aux zones {@code active = true} dans la dimension
 * courante.
 *
 * - {@code geometry} : JSON Leaflet-Geoman (Feature avec geometry =
 *   Polygon/Circle/Rectangle, coords en {@code [lng, lat]} = {@code [worldX, -worldZ]}).
 * - {@code dim}      : "overworld" / "nether" / "end".
 * - {@code shape}    : type Geoman ("Rectangle", "Polygon", "Circle") — pratique
 *   pour le bot qui n'a pas besoin de parser la géométrie pour les types simples.
 */
object SearchZonesTable : LongIdTable("search_zones") {
    val name = varchar("name", 128)
    val dim = varchar("dim", 16).index()
    val shape = varchar("shape", 32)
    val geometry = text("geometry")
    val active = bool("active").default(true).index()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}
