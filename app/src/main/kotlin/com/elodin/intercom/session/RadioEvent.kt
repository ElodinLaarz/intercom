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

    data class Failed(
        val reason: String,
    ) : RadioEvent

    data class Status(
        val text: String,
    ) : RadioEvent
}
