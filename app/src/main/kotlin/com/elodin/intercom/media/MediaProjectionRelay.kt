package com.elodin.intercom.media

import android.media.projection.MediaProjection

/**
 * Process-wide rendezvous for the [MediaProjection] between [MediaShareService]
 * (which must acquire it from inside the running foreground service) and
 * [MediaShareController] (which owns the capture/encode pipeline). Decoupled so
 * the service need not know about the radio/session layer.
 *
 * Order-independent: whichever side arrives first parks its value and the other
 * picks it up. The controller registers a consumer for its lifetime; the service
 * publishes once consent is granted.
 */
internal object MediaProjectionRelay {
    private val lock = Any()
    private var consumer: ((MediaProjection) -> Unit)? = null
    private var revokeConsumer: (() -> Unit)? = null
    private var pending: MediaProjection? = null

    fun setConsumer(next: ((MediaProjection) -> Unit)?) {
        val deliver =
            synchronized(lock) {
                consumer = next
                val parked = pending
                if (next != null && parked != null) {
                    pending = null
                    parked
                } else {
                    null
                }
            }
        if (deliver != null && next != null) next(deliver)
    }

    fun publish(projection: MediaProjection) {
        val target =
            synchronized(lock) {
                val current = consumer
                if (current == null) pending = projection
                current
            }
        target?.invoke(projection)
    }

    fun clearPending() {
        synchronized(lock) { pending = null }
    }

    /** Register the consumer notified when the projection is revoked (service onStop). */
    fun setRevokeConsumer(next: (() -> Unit)?) {
        synchronized(lock) { revokeConsumer = next }
    }

    /** The projection was revoked/stopped — drop any parked value and notify. */
    fun revoke() {
        val target =
            synchronized(lock) {
                pending = null
                revokeConsumer
            }
        target?.invoke()
    }
}
