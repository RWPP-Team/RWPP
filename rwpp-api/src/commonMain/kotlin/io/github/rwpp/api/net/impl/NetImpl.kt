/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.koin.core.annotation.Single
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket

private val log = logger()

/** Shared networking service; only opening a browser is platform-specific. */
@Single(binds = [Net::class, Initialization::class])
class NetImpl : Net {
    @Deprecated("Use packetRegistry and typed PacketKey registrations")
    override val packetDecoders: MutableMap<Int, (DataInputStream) -> Packet> = ConcurrentHashMap()

    @Deprecated("Use packetRegistry and typed PacketKey registrations")
    override val listeners: MutableMap<Int, MutableList<(Connection?, Packet) -> Boolean>> = ConcurrentHashMap()
    override val packetRegistry: PacketRegistry = PacketRegistry()
    override val payloadRegistry: PayloadRegistry = PayloadRegistry()

    override val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("Accept-Encoding").build())
        })
        // The official masters are far from some regions; the previous 5 s read deadline failed
        // the whole room list on a single slow handshake.
        .connectTimeout(10_000L, TimeUnit.MILLISECONDS)
        .readTimeout(15_000L, TimeUnit.MILLISECONDS)
        .build()

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val roomListProvider: MutableMap<String, suspend () -> List<RoomDescription>> = mutableMapOf()
    override val roomListHostProtocol:
            MutableMap<String, (maxPlayer: Int, enableMods: Boolean, isPublic: Boolean) -> String> = mutableMapOf()

    private val roomListCacheLock = Any()
    private var roomListCacheValue: List<RoomDescription>? = null
    private var roomListCacheLoaded = false

    override fun cachedRoomList(): List<RoomDescription>? = synchronized(roomListCacheLock) {
        if (!roomListCacheLoaded) {
            roomListCacheLoaded = true
            roomListCacheValue = runCatching { readRoomListCache() }
                .onFailure { log.warn("Could not read the room list cache", it) }
                .getOrNull()
        }
        roomListCacheValue
    }

    override fun storeRoomListCache(rooms: List<RoomDescription>) {
        synchronized(roomListCacheLock) {
            roomListCacheValue = rooms
            roomListCacheLoaded = true
            runCatching { writeRoomListCache(rooms) }
                .onFailure { log.warn("Could not persist the room list cache", it) }
        }
    }

    override fun clearRoomListCache() {
        synchronized(roomListCacheLock) {
            roomListCacheValue = null
            roomListCacheLoaded = true
            runCatching { roomListCachePath.delete() }
                .onFailure { log.warn("Could not delete the room list cache", it) }
        }
    }

    private val roomListCachePath: File by lazy {
        val preferences = runCatching { GameEngine.getInstance().settings.preferencesPath }.getOrNull()
        val directory = preferences?.let { File(it).parentFile } ?: File(".")
        File(directory, "rwpp-room-list.cache")
    }

    private fun readRoomListCache(): List<RoomDescription>? {
        val file = roomListCachePath
        if (!file.isFile) return null
        val text = file.readText()
        if (text.isBlank()) return null
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::decodeCachedRoom)
            .toList()
            .ifEmpty { null }
    }

    private fun writeRoomListCache(rooms: List<RoomDescription>) {
        val file = roomListCachePath
        file.parentFile?.mkdirs()
        file.writeText(rooms.joinToString("\n", transform = ::encodeCachedRoom))
    }

    private fun encodeCachedRoom(room: RoomDescription): String = listOf(
        room.uuid,
        room.roomOwner,
        room.gameVersion.toString(),
        room.netWorkAddress,
        room.localAddress,
        room.port.toString(),
        room.isOpen.toString(),
        room.creator,
        room.requiredPassword.toString(),
        room.mapName,
        room.mapType,
        room.status,
        room.version,
        room.isLocal.toString(),
        room.displayMapName,
        (room.playerCurrentCount ?: -1).toString(),
        (room.playerMaxCount ?: -1).toString(),
        room.isUpperCase.toString(),
        room.uuid2,
        room.unknown.toString(),
        room.mods,
        room.roomId.toString(),
    ).joinToString(ROOM_LIST_FIELD_SEPARATOR)

    private fun decodeCachedRoom(line: String): RoomDescription? {
        val fields = line.split(ROOM_LIST_FIELD_SEPARATOR)
        if (fields.size < 22) return null
        val current = fields[15].toIntOrNull()?.takeIf { it >= 0 }
        val maximum = fields[16].toIntOrNull()?.takeIf { it >= 0 }
        return RoomDescription(
            uuid = fields[0],
            roomOwner = fields[1],
            gameVersion = fields[2].toIntOrNull() ?: return null,
            netWorkAddress = fields[3],
            localAddress = fields[4],
            port = fields[5].toLongOrNull() ?: return null,
            isOpen = fields[6].toBooleanStrictOrNull() ?: return null,
            creator = fields[7],
            requiredPassword = fields[8].toBooleanStrictOrNull() ?: return null,
            mapName = fields[9],
            mapType = fields[10],
            status = fields[11],
            version = fields[12],
            isLocal = fields[13].toBooleanStrictOrNull() ?: return null,
            displayMapName = fields[14],
            playerCurrentCount = current,
            playerMaxCount = maximum,
            isUpperCase = fields[17].toBooleanStrictOrNull() ?: return null,
            uuid2 = fields[18],
            unknown = fields[19].toBooleanStrictOrNull() ?: return null,
            mods = fields[20],
            roomId = fields[21].toIntOrNull() ?: return null,
        )
    }

    override fun init() {
        io.github.rwpp.api.net.packets.ModTransferHost.install(
            io.github.rwpp.api.event.EventOwner(io.github.rwpp.api.ApiRuntime.extensionContext), this,
        )
        roomListHostProtocol["RCN"] = { maxPlayer, enableMods, isPublic ->
            when {
                enableMods && isPublic -> "Rmodupp$maxPlayer"
                enableMods -> "Rmodp$maxPlayer"
                isPublic -> "Rnewupp$maxPlayer"
                else -> "Rnewp$maxPlayer"
            }
        }
        roomListHostProtocol["SCN"] = { maxPlayer, enableMods, isPublic ->
            when {
                enableMods && isPublic -> "Smodupp$maxPlayer"
                enableMods -> "Smodp$maxPlayer"
                isPublic -> "Snewupp$maxPlayer"
                else -> "Snewp$maxPlayer"
            }
        }
    }

    override fun sendPacketToServer(packet: Packet) {
        require(packet.type != PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID) {
            "Native packet 1000 is reserved for the API-owned namespaced payload envelope"
        }
        val payload = packetRegistry.encodeOutgoing(packet, PacketDirection.CLIENT_TO_SERVER)
        GameEngine.getInstance().networkEngine.sendPacketToServer(GamePacket(packet.type).apply { data = payload })
    }

    override fun sendPacketToClients(packet: Packet) {
        require(packet.type != PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID) {
            "Native packet 1000 is reserved for the API-owned namespaced payload envelope"
        }
        val payload = packetRegistry.encodeOutgoing(packet, PacketDirection.SERVER_TO_CLIENT)
        GameEngine.getInstance().networkEngine.sendPacketToClients(GamePacket(packet.type).apply { data = payload })
    }

    override fun <T : Any> sendPayloadToServer(key: PayloadKey<T>, value: T) {
        val network = GameEngine.getInstance().networkEngine
        val connection = requireJoinedClient(network)
        require(key.direction.allows(PacketDirection.CLIENT_TO_SERVER)) {
            "Payload '${key.namespace}' is not declared for client-to-server traffic"
        }
        val envelope = payloadRegistry.encodeOutgoing(
            key = key,
            value = value,
            connection = connection,
            outgoingDirection = PacketDirection.CLIENT_TO_SERVER,
            isServer = false,
        )
        network.sendPacketToServer(GamePacket(PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID).apply { data = envelope })
    }

    override fun <T : Any> sendPayloadToClients(key: PayloadKey<T>, value: T): Int {
        val network = GameEngine.getInstance().networkEngine
        requireJoinedServer(network)
        require(key.direction.allows(PacketDirection.SERVER_TO_CLIENT)) {
            "Payload '${key.namespace}' is not declared for server-to-client traffic"
        }
        var sent = 0
        for (candidate in network.connections) {
            val connection = candidate as? Connection ?: continue
            if (!isCurrentConnection(network, connection) || !payloadRegistry.supports(
                    connection,
                    key,
                    isServer = true
                )
            ) continue
            val envelope = payloadRegistry.encodeOutgoing(
                key = key,
                value = value,
                connection = connection,
                outgoingDirection = PacketDirection.SERVER_TO_CLIENT,
                isServer = true,
            )
            network.sendPacketOnConnection(
                connection,
                GamePacket(PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID).apply { data = envelope },
            )
            sent++
        }
        return sent
    }

    override fun <T : Any> sendPayloadToClient(connection: Connection, key: PayloadKey<T>, value: T) {
        val network = GameEngine.getInstance().networkEngine
        requireJoinedServer(network)
        require(isCurrentConnection(network, connection)) {
            "Cannot send an RWML payload to a disconnected or stale client connection"
        }
        require(key.direction.allows(PacketDirection.SERVER_TO_CLIENT)) {
            "Payload '${key.namespace}' is not declared for server-to-client traffic"
        }
        val envelope = payloadRegistry.encodeOutgoing(
            key = key,
            value = value,
            connection = connection,
            outgoingDirection = PacketDirection.SERVER_TO_CLIENT,
            isServer = true,
        )
        network.sendPacketOnConnection(
            connection,
            GamePacket(PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID).apply { data = envelope },
        )
    }

    override fun negotiatePayloadsToServer() {
        val network = GameEngine.getInstance().networkEngine
        requireJoinedClient(network)
        network.sendPacketToServer(capabilityPacket())
    }

    override fun negotiatePayloadsToClient(connection: Connection) {
        val network = GameEngine.getInstance().networkEngine
        requireJoinedServer(network)
        require(isCurrentConnection(network, connection)) {
            "Cannot negotiate RWML payloads with a disconnected or stale client connection"
        }
        network.sendPacketOnConnection(connection, capabilityPacket())
    }

    override fun supportsPayload(connection: Connection?, key: PayloadKey<*>): Boolean {
        val network = GameEngine.getInstance().networkEngine
        if (network.isServer) {
            if (!isJoinedServer(network) || connection == null || !isCurrentConnection(
                    network,
                    connection
                )
            ) return false
        } else {
            val currentConnection = if (isJoinedClient(network)) network.getClientConnection() else null
            if (currentConnection == null || (connection != null && connection !== currentConnection)) return false
            if (!isCurrentConnection(network, currentConnection)) return false
            return payloadRegistry.supports(currentConnection, key, isServer = false)
        }
        return payloadRegistry.supports(connection, key, isServer = network.isServer)
    }

    @Suppress("DEPRECATION")
    override fun dispatchNativePacket(
        packetType: Int,
        payload: ByteArray?,
        connection: Connection?,
        incomingDirection: PacketDirection,
    ): Boolean {
        if (packetType == PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID) {
            val network = GameEngine.getInstance().networkEngine
            val peerIsCurrent = if (network.isServer) {
                isJoinedServer(network) && connection != null && isCurrentConnection(network, connection)
            } else {
                val currentConnection = if (isJoinedClient(network)) network.getClientConnection() else null
                currentConnection != null && connection === currentConnection && isCurrentConnection(
                    network,
                    currentConnection
                )
            }
            if (!peerIsCurrent) {
                log.warn("Dropped RWML payload envelope outside the active joined room")
                return true
            }
            val shouldReply = payloadRegistry.dispatchEnvelope(
                payload = payload,
                connection = connection,
                incomingDirection = incomingDirection,
                isServer = network.isServer,
            )
            if (shouldReply) {
                if (network.isServer) {
                    if (connection != null && isJoinedServer(network) && isCurrentConnection(network, connection)) {
                        network.sendPacketOnConnection(connection, capabilityPacket())
                    }
                } else if (isJoinedClient(network) && connection != null &&
                    connection === network.getClientConnection() && isCurrentConnection(network, connection)
                ) {
                    network.sendPacketToServer(capabilityPacket())
                }
            }
            // Packet 1000 belongs exclusively to the API envelope, including malformed frames.
            return true
        }

        when (packetRegistry.dispatch(packetType, payload, connection, incomingDirection)) {
            PacketDispatchResult.CONSUME -> return true
            PacketDispatchResult.PASS -> return false
            PacketDispatchResult.UNREGISTERED -> Unit
        }

        // Preserve source compatibility for callers still using the old raw maps. The packet limit
        // and reserved IDs also apply to that path, while direction metadata is unavailable there.
        if (InternalPacketType.isNative(packetType)) return false
        val packetDecoder = packetDecoders[packetType] ?: return false
        val packetListeners = listeners[packetType]?.toList().orEmpty()
        if (packetListeners.isEmpty()) return false
        val packetBytes = payload ?: ByteArray(0)
        if (packetBytes.size > LEGACY_MAX_PAYLOAD_BYTES) {
            log.warn("Dropped legacy extension packet $packetType: payload is ${packetBytes.size} bytes.")
            return true
        }
        val decoded = try {
            packetDecoder(DataInputStream(ByteArrayInputStream(packetBytes)))
        } catch (failure: Throwable) {
            log.error("Failed to decode legacy extension packet $packetType.", failure)
            return true
        }
        if (decoded.type != packetType) {
            log.warn("Dropped legacy extension packet $packetType: decoder returned type ${decoded.type}.")
            return true
        }
        return try {
            packetListeners.any { it(connection, decoded) }
        } catch (failure: Throwable) {
            log.error("Legacy extension packet receiver failed for ID $packetType.", failure)
            true
        }
    }

    override fun openUriInBrowser(uri: String) = apiOpenUriInBrowser(uri)

    private fun capabilityPacket(): GamePacket =
        GamePacket(PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID).apply {
            data = payloadRegistry.encodeCapabilitiesFrame()
        }

    private fun isJoinedClient(network: NetworkEngine): Boolean =
        network.networked && !network.isServer && network.localTeam != null

    private fun isJoinedServer(network: NetworkEngine): Boolean =
        network.networked && network.isServer && network.localTeam != null

    private fun requireJoinedClient(
        network: NetworkEngine,
    ): Connection {
        require(isJoinedClient(network)) {
            "RWML payloads are available to clients only after joining a network room"
        }
        val connection = network.getClientConnection()
        require(connection != null && isCurrentConnection(network, connection)) {
            "No active server connection is available for RWML payload traffic"
        }
        return connection
    }

    private fun requireJoinedServer(network: NetworkEngine) {
        require(isJoinedServer(network)) {
            "RWML payloads are available to servers only after hosting a network room"
        }
    }

    private fun isCurrentConnection(
        network: NetworkEngine,
        connection: Connection,
    ): Boolean = connection.connected && network.connections.any { it === connection }

    private companion object {
        const val LEGACY_MAX_PAYLOAD_BYTES = 1024 * 1024
        const val ROOM_LIST_FIELD_SEPARATOR = "\u0001"
    }
}

internal expect fun apiOpenUriInBrowser(uri: String)
