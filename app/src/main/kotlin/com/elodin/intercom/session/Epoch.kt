package com.elodin.intercom.session

internal class Epoch(
    val id: Long,
    private val endpoint: RadioEndpoint,
) : AutoCloseable {
    var closed: Boolean = false
        private set

    override fun close() {
        if (closed) return

        closed = true
        endpoint.endEpoch()
    }
}
