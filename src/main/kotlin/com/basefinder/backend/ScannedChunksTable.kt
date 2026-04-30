package com.basefinder.backend

import org.jetbrains.exposed.sql.Table

/**
 * Schéma {@code scanned_chunks}.
 *
 * Une ligne par (dim, chunk_x, chunk_z) déjà observé par un bot. Source de vérité
 * pour la coverage layer du dashboard. Insertion en {@code INSERT OR IGNORE} :
 * un chunk re-scanné laisse la ligne intacte (la première date de scan est conservée).
 *
 * Migration Postgres : changer le primary key à un index UNIQUE multi-colonnes
 * et ajouter `id BIGSERIAL` ; SQLite tolère le PK composite.
 */
object ScannedChunksTable : Table("scanned_chunks") {
    val dim = varchar("dim", 16)
    val chunkX = integer("chunk_x")
    val chunkZ = integer("chunk_z")
    val scannedAt = long("scanned_at")

    override val primaryKey = PrimaryKey(dim, chunkX, chunkZ, name = "pk_scanned_chunks")
}
