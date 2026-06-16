package com.elodin.intercom.session

internal interface RadioEndpoint {
    fun start(): Boolean

    fun beginEpoch(epochId: Long) = Unit

    fun endEpoch() = Unit

    fun stop()
}

internal interface RadioEndpointFactory {
    fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint

    fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint
}
