package com.elodin.intercom.wifidirect

import android.util.Log
import com.elodin.intercom.media.MediaWire
import com.elodin.intercom.proto.MediaFrame
import com.elodin.intercom.proto.Proto
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The shared-media TCP channel that rides the existing Wi-Fi Direct group on a
 * SEPARATE port ([Proto.MEDIA_STREAM_PORT]) from the voice socket (9753). It is
 * deliberately independent of the voice [WifiDirectHostRadio]/[WifiDirectGuestRadio]:
 * the voice wire path is fixed-stride and desync-prone, so media gets its own
 * length-delimited stream ([MediaWire]) and its own socket, leaving the voice
 * path untouched.
 *
 * Bidirectional but one sender at a time — either peer may share. The group is
 * already formed and the wire epoch already agreed by the voice link, so this
 * carries no handshake of its own: [send] writes encoded frames and the worker
 * thread delivers received frames to [onFrame].
 *
 * One link per `Linked` epoch (V2_PLAN rule 4): the worker connects once,
 * pumps frames, and calls [onClosed] exactly once when it ends — on the worker
 * thread, so the controller can tear its RX codec/playout down on the same
 * thread that built and fed them (MediaCodec is not safe to release from
 * another thread mid-decode). [close] from any thread unblocks the worker.
 */
@Suppress("TooManyFunctions")
internal class WifiDirectMediaLink(
    private val isHost: Boolean,
    private val peerAddress: String,
    private val onFrame: (MediaFrame) -> Unit,
    private val onClosed: () -> Unit,
) {
    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var worker: Thread? = null

    // Single writer thread for ALL socket writes (audio frames + sentinels), so
    // no caller — including the main thread that emits share-start/stop sentinels
    // — ever does network I/O on its own thread (NetworkOnMainThreadException).
    // Bounded + discard-oldest: under congestion the freshest frames win and
    // send() never blocks the caller.
    @Volatile
    private var txExecutor: ThreadPoolExecutor? = null

    fun start() {
        if (running) return
        running = true
        txExecutor =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(TX_QUEUE_DEPTH),
                ThreadPoolExecutor.DiscardOldestPolicy(),
            )
        worker = thread(isDaemon = true, name = "wfd-media") { run() }
    }

    /** Best-effort, non-blocking: enqueue the frame for the writer thread. */
    fun send(frame: MediaFrame): Boolean {
        val ex = txExecutor ?: return false
        return try {
            ex.execute { writeFrame(frame) }
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "MEDIA link tx rejected (closing): ${e.message}")
            false
        }
    }

    private fun writeFrame(frame: MediaFrame) {
        val s = socket ?: return
        try {
            MediaWire.writeFrame(s.outputStream, frame)
        } catch (e: IOException) {
            Log.w(TAG, "MEDIA link tx dropped: ${e.message}")
        }
    }

    // Join the worker before returning so its onClosed → RX teardown (codec/
    // playout release) finishes before the caller builds the next epoch's link.
    // This is what keeps the RX MediaCodec single-threaded across reconnects.
    // Bounded so a worker wedged in a blocking AudioTrack write can't hang the
    // caller (the worker is a daemon and will exit once the socket is closed).
    fun close() {
        running = false
        txExecutor?.shutdownNow()
        txExecutor = null
        closeServer()
        closeSocket(socket)
        socket = null
        val w = worker
        worker = null
        if (w != null && w !== Thread.currentThread()) {
            try {
                w.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun run() {
        try {
            val link = if (isHost) accept() else connect()
            if (link != null) serve(link)
        } finally {
            onClosed()
        }
    }

    // Host: accept the single guest media connection, then stop listening.
    private fun accept(): Socket? =
        try {
            val server = ServerSocket(Proto.MEDIA_STREAM_PORT)
            serverSocket = server
            Log.i(TAG, "MEDIA link listening port=${Proto.MEDIA_STREAM_PORT}")
            val client = server.accept()
            client.tcpNoDelay = true
            closeServer()
            client
        } catch (e: IOException) {
            if (running) Log.w(TAG, "MEDIA link accept failed: ${e.message}")
            null
        }

    // Guest: dial the group owner's media port (retry; the host may lag).
    private fun connect(): Socket? {
        var attempt = 0
        while (running && attempt < MAX_CONNECT_ATTEMPTS) {
            val client = tryConnect()
            if (client != null) return client
            attempt += 1
            if (running && attempt < MAX_CONNECT_ATTEMPTS) sleep(CONNECT_RETRY_MS)
        }
        if (running) Log.w(TAG, "MEDIA link connect to $peerAddress failed")
        return null
    }

    private fun tryConnect(): Socket? =
        try {
            val client = Socket()
            client.tcpNoDelay = true
            client.connect(
                InetSocketAddress(peerAddress, Proto.MEDIA_STREAM_PORT),
                CONNECT_TIMEOUT_MS,
            )
            client
        } catch (e: IOException) {
            Log.w(TAG, "MEDIA link connect attempt failed: ${e.message}")
            null
        }

    private fun serve(client: Socket) {
        socket = client
        Log.i(TAG, "MEDIA link connected peer=${client.inetAddress?.hostAddress}")
        try {
            val input = client.inputStream
            while (running) {
                val frame = MediaWire.readFrame(input) ?: continue
                onFrame(frame)
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "MEDIA link rx ended: ${e.message}")
        } finally {
            socket = null
            closeSocket(client)
        }
    }

    private fun closeServer() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "MEDIA link close server: ${e.message}")
        }
        serverSocket = null
    }

    private fun closeSocket(target: Socket?) {
        try {
            target?.close()
        } catch (e: IOException) {
            Log.w(TAG, "MEDIA link close socket: ${e.message}")
        }
    }

    private fun sleep(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "INTERCOM"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val CONNECT_RETRY_MS = 500L
        private const val MAX_CONNECT_ATTEMPTS = 8
        private const val JOIN_TIMEOUT_MS = 600L
        private const val TX_QUEUE_DEPTH = 16
    }
}
