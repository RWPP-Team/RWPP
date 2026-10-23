package io.github.rwpp.api.net.impl

/** Optional framed socket contract consumed by the game's shared hooks. */
interface NativePacketSocket {
    val transportName: String
    /** A client that fell back to a raw JDK socket uses the original game workers. */
    val handlesNativePackets: Boolean get() = true
    fun startReceiving(limit: () -> Int, receive: (Int, ByteArray) -> Unit, failure: (String) -> Unit)
    /**
     * [fastReceive] may consume a frame by returning true. It must be short and
     * nonblocking; returning false must leave the frame entirely unconsumed.
     * Backends may invoke it on an I/O thread only when earlier callbacks have
     * finished, preserving the order of fast and ordinary frames. The default
     * runs both callbacks through the original receiving path.
     */
    fun startReceiving(
        limit: () -> Int,
        receive: (Int, ByteArray) -> Unit,
        failure: (String) -> Unit,
        fastReceive: ((Int, ByteArray) -> Boolean)?,
    ) = startReceiving(limit, { type, bytes ->
        if (fastReceive?.invoke(type, bytes) != true) receive(type, bytes)
    }, failure)
    fun send(type: Int, bytes: ByteArray, delayAfterMillis: Int = -1): Boolean
    fun closeAfterWrites()
    companion object { const val MAX_PACKET_BYTES = 50_000_000 }
}
