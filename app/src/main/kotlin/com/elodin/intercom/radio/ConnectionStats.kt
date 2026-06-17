package com.elodin.intercom.radio

data class ConnectionStats(
    val txBps: Long = 0,
    val txFps: Long = 0,
    val txBusyPct: Long = 0,
    val rxBps: Long = 0,
    val rxFps: Long = 0,
    val rxBusyPct: Long = 0,
    val rxMaxBusyMs: Long = 0,
)
