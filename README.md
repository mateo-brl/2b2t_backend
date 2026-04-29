# 2b2t-backend

Backend de télémétrie pour le bot BaseFinder (cf. [`2b2t_addons`](../2b2t_addons)).

Phase 0 du Jalon 2 — endpoint d'ingest NDJSON + ring in-memory pour valider le
chemin bot → backend de bout en bout. Persistance Postgres + dashboard React
arrivent dans les phases suivantes.

## Stack

- Kotlin 2.0 / JVM 21
- Ktor 3.0 (Netty)
- kotlinx.serialization
- Logback

## Run

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew run
```

Variables :
- `PORT` (défaut `8080`)
- `HOST` (défaut `127.0.0.1`)

## Endpoints

| Méthode | Chemin         | Description                                                      |
|---------|----------------|------------------------------------------------------------------|
| GET     | `/v1/health`   | Statut + compteurs.                                              |
| POST    | `/v1/ingest`   | NDJSON streaming (1 event/ligne, `Content-Type: application/x-ndjson`). |
| GET     | `/v1/events`   | Dump des derniers events (param `limit` ≤ 1000, défaut 100).     |

## Activer côté bot

Ajouter aux JVM args du launcher Minecraft :

```
-Dbasefinder.backend.url=http://127.0.0.1:8080
```

Le bot conserve son sink fichier NDJSON (`<gameDir>/rusherhack/basefinder/telemetry.ndjson`)
en parallèle — le sink HTTP est additif (cf. `CompositeSink` dans le bot).
