package com.elodin.intercom.session

internal enum class LinkRole {
    Host,
    Guest,
}

internal sealed interface LinkState {
    val role: LinkRole?
    val detail: String

    val hosting: Boolean
        get() = role == LinkRole.Host

    val guesting: Boolean
        get() = role == LinkRole.Guest

    data class Idle(
        override val detail: String = IDLE_DETAIL,
    ) : LinkState {
        override val role: LinkRole? = null
    }

    data class Hosting(
        val psm: Int? = null,
        override val detail: String = "Starting host",
    ) : LinkState {
        override val role: LinkRole = LinkRole.Host
    }

    data class Scanning(
        override val detail: String = "Scanning for host",
    ) : LinkState {
        override val role: LinkRole = LinkRole.Guest
    }

    data class Connecting(
        val peer: String,
        override val detail: String = "Connecting to peer",
    ) : LinkState {
        override val role: LinkRole = LinkRole.Guest
    }

    data class Linked(
        val epoch: Epoch,
        override val role: LinkRole,
        val peer: String,
        val psm: Int,
        val wireEpoch: Long,
        override val detail: String,
    ) : LinkState

    // The link dropped but the active endpoint is self-healing (Wi-Fi Direct
    // auto-rejoin). Role is retained so the UI stays in Host/Guest mode rather
    // than falling back to Idle while the radio re-establishes the peer.
    data class Reconnecting(
        override val role: LinkRole,
        override val detail: String = "Reconnecting…",
    ) : LinkState

    companion object {
        const val IDLE_DETAIL = "Idle - Host to advertise, Guest to scan"
    }
}
