package com.elodin.intercom.session

internal sealed interface RadioEvent {
    data class Advertising(
        val psm: Int,
        val text: String,
    ) : RadioEvent

    data class Found(
        val peer: String,
        val text: String,
    ) : RadioEvent

    data class Linked(
        val peer: String,
        val psm: Int,
        val wireEpoch: Long = 0,
    ) : RadioEvent

    data class LinkLost(
        val reason: String,
    ) : RadioEvent

    // A recoverable drop: the radio endpoint is self-healing and stays alive,
    // so the session must NOT tear it down (unlike LinkLost/Failed). Emitted by
    // the Wi-Fi Direct radios while they re-discover/re-accept the peer.
    data class Reconnecting(
        val reason: String,
    ) : RadioEvent

    data class Failed(
        val reason: String,
    ) : RadioEvent

    data class Status(
        val text: String,
    ) : RadioEvent
}
