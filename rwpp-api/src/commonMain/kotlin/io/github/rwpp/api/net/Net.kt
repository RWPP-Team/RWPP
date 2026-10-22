/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net

import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.event.EventOwner
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import io.github.rwpp.api.logging.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.koin.core.component.KoinComponent
import java.io.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.full.createInstance
import kotlin.time.Duration.Companion.milliseconds

private val log = logger()

interface Net :
    KoinComponent,
    Initialization {
    /**
     * Legacy raw packet decoders. New extensions should use [packetRegistry] and a [PacketKey].
     */
    @Deprecated("Use packetRegistry and typed PacketKey registrations")
    val packetDecoders: MutableMap<Int, (DataInputStream) -> Packet>

    /**
     * Legacy raw packet listeners. New extensions should use [packetRegistry] and a [PacketKey].
     *
     * If the lambda returns true, then the packet would not be resolved by the game.
     */
    @Deprecated("Use packetRegistry and typed PacketKey registrations")
    val listeners: MutableMap<Int, MutableList<(Connection?, Packet) -> Boolean>>

    /** Typed receivers for native extension packet IDs in the 500..999 range. */
    val packetRegistry: PacketRegistry

    /**
     * Namespaced payload receivers carried by the API-owned native packet 1000 envelope. Payload
     * traffic is supported after ROOM_JOINED only: the native session must be active, the local
     * team assigned, and the peer connection still present in the current engine.
     */
    val payloadRegistry: PayloadRegistry

    /**
     * Default Okhttp3 client.
     */
    val client: OkHttpClient

    /**
     * The coroutine scope for network operations.
     */
    val scope: CoroutineScope

    /**
     * The map of room list providers, key is the name, value is a lambda that returns a list of RoomDescription.
     */
    val roomListProvider: MutableMap<String, suspend () -> List<RoomDescription>>

    /**
     * The map of room list host protocols, key is the name, value is a lambda that returns the host url of the room list.
     */
    val roomListHostProtocol: MutableMap<String, (maxPlayer: Int, enableMods: Boolean, isPublic: Boolean) -> String>

    /**
     * Send a packet to the server.
     */
    fun sendPacketToServer(packet: Packet)

    /**
     * Send a packet to all clients. (if host)
     * @see com.corrodinggames.rts.gameFramework.network.NetworkEngine.isServer
     */
    fun sendPacketToClients(packet: Packet)

    /** Sends a namespaced payload to the server after that peer advertised the same payload key. */
    fun <T : Any> sendPayloadToServer(key: PayloadKey<T>, value: T)

    /**
     * Sends a namespaced payload to clients that advertised the same payload key. Clients that
     * have not negotiated this key are skipped. Returns the number of clients to which it queued a send;
     * this allows mixed rooms with vanilla clients without sending them an unknown packet.
     */
    fun <T : Any> sendPayloadToClients(key: PayloadKey<T>, value: T): Int

    /** Sends a namespaced payload to one client after checking its advertised capability. */
    fun <T : Any> sendPayloadToClient(connection: Connection, key: PayloadKey<T>, value: T)

    /**
     * Starts or refreshes negotiation with the server. Call after joining a room and after any
     * later local registration changes. The peer answers the first advertisement; later registration
     * changes must be advertised by both endpoints. The API does not probe vanilla peers.
     */
    fun negotiatePayloadsToServer()

    /**
     * Starts or refreshes negotiation with one client. Call after that client joins and after any
     * later local registration changes. The peer answers the first advertisement; later registration
     * changes must be advertised by both endpoints.
     */
    fun negotiatePayloadsToClient(connection: Connection)

    /** True only when the local key and the peer's advertised key metadata match. */
    fun supportsPayload(connection: Connection?, key: PayloadKey<*>): Boolean

    /** Dispatch a native system packet to extension receivers. Called by the API's native packet hook. */
    fun dispatchNativePacket(
        packetType: Int,
        payload: ByteArray?,
        connection: Connection?,
        incomingDirection: PacketDirection,
    ): Boolean

    fun openUriInBrowser(uri: String)

    fun downloadFile(url: String, outputFile: File, progressCallback: (progress: Float) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                progressCallback(-1f)

                log.error(e.stackTraceToString())
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code $response")
                }

                var downloadedBytes: Long = 0
                val body = response.body
                val contentLength = body.contentLength()
                var inputStream: InputStream? = null
                var outputStream: RandomAccessFile? = null

                try {
                    inputStream = body.byteStream()
                    outputStream = RandomAccessFile(outputFile, "rw")

                    val buffer = ByteArray(4096)
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        progressCallback(
                            (downloadedBytes.toFloat() / contentLength.toFloat()).apply {
                                log.info("downloading: $this")
                            },
                        )
                    }

                    log.info("Download completed: ${outputFile.absoluteFile}")
                } catch (e: Exception) {
                    log.error(e.stackTraceToString())
                    progressCallback(-1f)
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                }
            }
        })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getRoomListFromSourceUrl(url: List<String>): List<RoomDescription> =
        withContext(Dispatchers.IO) {
            withTimeout(5000L.milliseconds) {
                val channel = Channel<Response>()

                url.forEach {
                    val request = Request.Builder()
                        .url(it)
                        .addHeader("User-Agent", "rw android 176 zh")
                        .addHeader("Language", "zh")
                        .get()
                        .build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful && channel.isEmpty) {
                                val result = channel.trySend(response)
                                if (result.isFailure) response.close()
                            } else {
                                response.close()
                            }
                        }
                    })
                }

                val result = channel.receive()

                val roomDescriptions = mutableListOf<RoomDescription>()

                result.body.source().use {
                    val read = it.readUtf8Line()
                    if (read?.contains("CORRODINGGAMES") != true) throw RuntimeException("Unknown header: $read")
                    var i = 0
                    while (true) {
                        val r = it.readUtf8Line()?.split(",") ?: break
                        val uuid = if (r[0].toIntOrNull() == 0) {
                            r[0] + i++
                        } else {
                            r[0]
                        }
                        try {
                            val desc = RoomDescription(
                                uuid,
                                r[1],
                                r[2].toInt(),
                                r[3],
                                r[4],
                                r[5].toLong(),
                                r[6].toBooleanStrict(),
                                r[7],
                                r[8].toBooleanStrict(),
                                r[9],
                                r[10],
                                r[11],
                                r[12],
                                r[13].toBooleanStrict(),
                                r[14],
                                r[15].toIntOrNull(),
                                r[16].toIntOrNull(),
                                r[17].toBooleanStrict(),
                                r[18].ifBlank { r[0] },
                                r[19].toBooleanStrict(),
                                r[20],
                                r[21].toInt(),
                            )

                            roomDescriptions.add(desc)
                        } catch (e: Throwable) {
                            throw RuntimeException("Parse error when reading: ${r.joinToString(",")}", e)
                        }
                    }
                }

                result.close()
                channel.close()

                roomDescriptions
            }
        }
}

/** Registers a typed receiver and returns a handle that can be owned by an [io.github.rwml.api.ExtensionContext]. */
fun <T : Packet> Net.registerPacketListener(
    key: PacketKey<T>,
    listener: (Connection?, T) -> Boolean,
): PacketListenerHandle = packetRegistry.register(key, listener)

/** Registers a typed receiver that is removed when its extension owner closes. */
fun <T : Packet> Net.registerPacketListener(
    owner: EventOwner,
    key: PacketKey<T>,
    listener: (Connection?, T) -> Boolean,
): PacketListenerHandle = packetRegistry.register(owner, key, listener)

@Suppress("UNCHECKED_CAST", "DEPRECATION")
@Deprecated("Use a namespaced PacketKey with a typed PacketCodec")
inline fun <reified T : Packet> Net.registerPacketListener(
    packetType: Int,
    noinline listener: (Connection?, T) -> Boolean,
) {
    require(packetType != PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID) {
        "Native packet 1000 is reserved for the API-owned namespaced payload envelope"
    }
    val method = T::class.java.getDeclaredMethod("readPacket", InputNetStream::class.java)
    packetDecoders[packetType] =
        {
            val p = T::class.createInstance()
            method.invoke(p, InputNetStream(it))
            p
        }
    listeners.getOrPut(packetType) { CopyOnWriteArrayList() }
        .add(listener as (Connection?, Packet) -> Boolean)
}
