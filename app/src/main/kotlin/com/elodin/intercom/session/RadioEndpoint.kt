package com.elodin.intercom.session

internal interface RadioEndpoint {
    fun start(): Boolean

    fun stop()
}

internal interface RadioEndpointFactory {
    fun host(onEvent: (RadioEvent) -> Unit): RadioEndpoint

    fun guest(onEvent: (RadioEvent) -> Unit): RadioEndpoint
}
