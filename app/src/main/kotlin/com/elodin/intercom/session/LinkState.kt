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

    companion object {
        const val IDLE_DETAIL = "Idle - Host to advertise, Guest to scan"
    }
}
