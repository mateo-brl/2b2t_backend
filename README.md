# 2b2t-backend

Backend de télémétrie pour le bot BaseFinder
(cf. [`2b2t_addons`](https://github.com/mateo-brl/2b2t_addons)),
consommé en live par
[`2b2t_dashboard`](https://github.com/mateo-brl/2b2t_dashboard).

Jalon 2 du projet : ingest NDJSON, dédup idempotent, persistance, lecture API.

## État actuel

| Phase | Statut | Contenu |
|-------|--------|---------|
| **0** — wire path | ✅ | `POST /v1/ingest` NDJSON + `GET /v1/health` + `GET /v1/events` |
| **1** — persistance | ✅ | Exposed + SQLite + HikariCP, dédup via `idempotency_key UNIQUE` |
| **2** — dashboard read | ✅ | CORS pour `localhost:5173/4173` (dev) |
| **3** — push temps-réel | ⏳ | SSE / WebSocket (à venir, remplace le polling 1 s du dashboard) |
| **4** — auth + prod | ⏳ | Discord OAuth gateway, Postgres swap (VPS Hetzner) |

## Stack

- Kotlin 2.0 / JVM 21
- Ktor 3.0.1 (Netty)
- Exposed 0.56 (ORM, dialect-agnostic) + HikariCP 6.2
- SQLite 3.47 en MVP (swap Postgres = changement de `JDBC_URL` uniquement)
- kotlinx.serialization (JSON)
- Logback

## Run

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew run
```

| Variable d'env | Défaut | Rôle |
|----------------|--------|------|
| `PORT` | `8080` | Port HTTP |
| `HOST` | `127.0.0.1` | Bind address |
| `JDBC_URL` | `jdbc:sqlite:data/basefinder.db` | URL JDBC (SQLite ou Postgres) |
| `DB_PATH` | `data/basefinder.db` | Chemin SQLite (ignoré si `JDBC_URL` set) |

Tests : `./gradlew test` — 7 tests d'intégration sur SQLite in-memory
(insert, dédup, malformed, ordre, round-trip payload).

## Endpoints

| Méthode | Chemin | Description |
|---------|--------|-------------|
| `GET`   | `/v1/health` | Statut + compteurs `{eventsReceived, eventsStored, version}` |
| `POST`  | `/v1/ingest` | NDJSON streaming (1 event/ligne, `Content-Type: application/x-ndjson`) |
| `GET`   | `/v1/events?limit=N` | Derniers events (N ≤ 1000, défaut 100) |

### Format wire d'un event (NDJSON v1)

```json
{
  "type": "bot_tick",
  "seq": 42,
  "ts_utc_ms": 1714400000000,
  "idempotency_key": "tick:42",
  "pos_y": 200,
  "hp": 20,
  "tps": 19.98,
  "scanned_chunks": 5217,
  "bases_found": 8,
  "flying": true,
  "flight_state": "CRUISING",
  "wp_index": 4,
  "wp_total": 500
}
```

Champs communs (tous les types) : `type`, `seq`, `ts_utc_ms`, `idempotency_key`.
Le payload spécifique au type suit (cf. [`2b2t_addons/EventSerializer.java`](https://github.com/mateo-brl/2b2t_addons/blob/main/src/main/java/com/basefinder/adapter/io/telemetry/EventSerializer.java)).

### Réponse `POST /v1/ingest`

```json
{ "accepted": 12, "duplicate": 1, "rejected": 0 }
```

- `accepted` — première fois qu'on voit cet `idempotency_key`.
- `duplicate` — clé déjà connue (silent dedupe via `INSERT OR IGNORE`).
- `rejected` — payload manque un champ obligatoire (`type`, `seq`, `ts_utc_ms`, `idempotency_key`).

## Schéma DB

Table unique `bot_events` (Exposed `LongIdTable`) :

| Colonne | Type | Notes |
|---------|------|-------|
| `id` | `BIGSERIAL` | PK auto-incrémentée |
| `seq` | `BIGINT` | Compteur monotone côté bot |
| `ts_utc_ms` | `BIGINT` | Horodatage origine |
| `type` | `VARCHAR(64)` | `bot_tick`, `base_found`, … (indexé) |
| `idempotency_key` | `VARCHAR(128)` | **UNIQUE** — race-safe dedup |
| `payload` | `TEXT` | JSON brut de l'event (pour forward-compat) |
| `received_at` | `BIGINT` | Horodatage serveur (indexé) |

Migration Postgres : changer `JDBC_URL` en `jdbc:postgresql://...` et basculer
`payload` en `JSONB` via une migration Exposed. Aucun code Kotlin à toucher.

## Activer côté bot

Ajouter aux JVM args du launcher Minecraft :

```
-Dbasefinder.backend.url=http://127.0.0.1:8080
```

Le bot conserve son sink fichier NDJSON
(`<gameDir>/rusherhack/basefinder/telemetry.ndjson`) en parallèle — le sink
HTTP est additif (cf. `CompositeSink` dans le bot). En cas d'indisponibilité
du backend, le bot continue à fonctionner et drop simplement les events HTTP
(file local intact).

## Licence

GPL-3.0 (cohérent avec le bot et le dashboard).
