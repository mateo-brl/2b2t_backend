package com.basefinder.backend

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * Fan-out vers tous les clients SSE connectés.
 *
 * `extraBufferCapacity` 256 absorbe des burst côté ingest sans bloquer
 * l'INSERT en DB. `DROP_OLDEST` privilégie la fraîcheur pour les
 * dashboards : si un client est lent, il rate les anciens events plutôt
 * que de freiner toute la chaîne.
 *
 * Pas de `replay` : les nouveaux abonnés ne reçoivent que les events
 * postérieurs à leur connexion. Le snapshot historique vient de la REST
 * `GET /v1/events?limit=N` (initial fetch côté dashboard).
 */
class EventBroadcaster {

    private val flow = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<JsonObject> = flow

    /** Non-suspending : the buffer absorbs spikes; if it overflows we drop oldest. */
    fun publish(event: JsonObject) {
        flow.tryEmit(event)
    }
}
