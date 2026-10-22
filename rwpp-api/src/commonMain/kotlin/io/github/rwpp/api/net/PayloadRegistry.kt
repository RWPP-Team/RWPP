/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net

import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.logging.logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private val log = logger()

/** An idempotent, owner-compatible payload receiver registration. */
class PayloadListenerHandle internal constructor(
    private val owner: EventOwner?,
    private val unregisterAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            unregisterAction()
        } finally {
            owner?.release(this)
        }
    }
}

/** A closeable payload type declaration for send-only endpoints. */
class PayloadTypeHandle internal constructor(
    private val owner: EventOwner?,
    private val unregisterAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            unregisterAction()
        } finally {
            owner?.release(this)
        }
    }
}

/**
 * Receivers for API-owned packet 1000. This envelope carries namespace/version metadata so
 * extensions do not coordinate a globally unique native packet number.
 *
 * Receiver callbacks run on the game's native network receive worker. They should decode or enqueue
 * work only; simulation changes must be handed to the game thread.
 */
class PayloadRegistry {
    private val routes = ConcurrentHashMap<PayloadId, Route>()
    private val routeLock = Any()
    private val capabilityLock = Any()
    private val remoteCapabilities = IdentityHashMap<Any, Map<PayloadId, PayloadCapability>>()

    /** Registers only the local codec/type; no inbound receiver is added. */
    fun <T : Any> registerType(key: PayloadKey<T>): PayloadTypeHandle = registerTypeInternal(null, key)

    /** Registers only the local codec/type and closes it with [owner]. */
    fun <T : Any> registerType(owner: EventOwner, key: PayloadKey<T>): PayloadTypeHandle =
        owner.own(registerTypeInternal(owner, key))

    fun <T : Any> register(
        key: PayloadKey<T>,
        receiver: (Connection?, T) -> Unit,
    ): PayloadListenerHandle = registerInternal(null, key, receiver)

    fun <T : Any> register(
        owner: EventOwner,
        key: PayloadKey<T>,
        receiver: (Connection?, T) -> Unit,
    ): PayloadListenerHandle = owner.own(registerInternal(owner, key, receiver))

    private fun <T : Any> registerInternal(
        owner: EventOwner?,
        key: PayloadKey<T>,
        receiver: (Connection?, T) -> Unit,
    ): PayloadListenerHandle {
        val id = PayloadId(key.namespace, key.version)
        @Suppress("UNCHECKED_CAST")
        val erasedReceiver: (Connection?, Any) -> Unit = { connection, value ->
            receiver(connection, value as T)
        }

        val route = synchronized(routeLock) {
            val selected = getOrCreateRoute(key)
            selected.registrationCount++
            selected.receivers.add(erasedReceiver)
            selected
        }

        return PayloadListenerHandle(owner) {
            synchronized(routeLock) {
                route.receivers.remove(erasedReceiver)
                route.registrationCount--
                removeRouteIfUnused(id, route)
            }
        }
    }

    private fun <T : Any> registerTypeInternal(
        owner: EventOwner?,
        key: PayloadKey<T>,
    ): PayloadTypeHandle {
        val id = PayloadId(key.namespace, key.version)
        val route = synchronized(routeLock) {
            getOrCreateRoute(key).also { it.registrationCount++ }
        }
        return PayloadTypeHandle(owner) {
            synchronized(routeLock) {
                route.registrationCount--
                removeRouteIfUnused(id, route)
            }
        }
    }

    /** Must be called with [routeLock] held. */
    private fun <T : Any> getOrCreateRoute(key: PayloadKey<T>): Route {
        val id = PayloadId(key.namespace, key.version)
        val existing = routes[id]
        if (existing != null) {
            if (existing.direction != key.direction ||
                existing.maxPayloadBytes != key.maxPayloadBytes ||
                existing.codec !== key.codec
            ) {
                throw IllegalArgumentException(
                    "Payload '${key.namespace}' version ${key.version} is already registered with " +
                        "different direction, size limit, or codec. Reuse the same PayloadKey and codec.",
                )
            }
            return existing
        }
        return Route(
            namespace = key.namespace,
            version = key.version,
            direction = key.direction,
            maxPayloadBytes = key.maxPayloadBytes,
            codec = key.codec,
            decode = { bytes -> key.codec.decode(bytes) },
            encode = { value ->
                @Suppress("UNCHECKED_CAST")
                key.codec.encode(value as T)
            },
        ).also { routes[id] = it }
    }

    private fun removeRouteIfUnused(id: PayloadId, route: Route) {
        if (route.registrationCount == 0 && route.receivers.isEmpty()) routes.remove(id, route)
    }

    /** True when both this endpoint and the selected remote peer advertised the same key. */
    @Suppress("UNUSED_PARAMETER")
    internal fun supports(connection: Connection?, key: PayloadKey<*>, isServer: Boolean): Boolean {
        if (connection == null || !connection.connected) return false
        val id = PayloadId(key.namespace, key.version)
        val route = routes[id] ?: return false
        if (!route.matches(key)) return false
        val remote = synchronized(capabilityLock) { remoteCapabilities[connection]?.get(id) } ?: return false
        return remote.canReceive && remote.direction == key.direction &&
            remote.maxPayloadBytes == key.maxPayloadBytes
    }

    /** Encodes a payload only after checking key registration, direction, and peer advertisement. */
    internal fun <T : Any> encodeOutgoing(
        key: PayloadKey<T>,
        value: T,
        connection: Connection?,
        outgoingDirection: PacketDirection,
        isServer: Boolean,
    ): ByteArray {
        require(key.direction.allows(outgoingDirection)) {
            "Payload '${key.namespace}' cannot be sent in direction $outgoingDirection"
        }
        val route = routes[PayloadId(key.namespace, key.version)]
            ?: throw IllegalStateException("Payload '${key.namespace}' version ${key.version} is not registered locally")
        require(route.matches(key)) {
            "Payload '${key.namespace}' version ${key.version} does not match the registered codec"
        }
        require(supports(connection, key, isServer)) {
            "The remote peer has not advertised support for payload '${key.namespace}' version ${key.version}"
        }
        val bytes = try {
            route.encode(value)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Codec failed to encode payload '${key.namespace}'", failure)
        }
        require(bytes.size <= key.maxPayloadBytes) {
            "Payload '${key.namespace}' is ${bytes.size} bytes; limit is ${key.maxPayloadBytes}"
        }
        val frame = encodePayloadFrame(key, bytes)
        if (outgoingDirection == PacketDirection.CLIENT_TO_SERVER) {
            require(frame.size <= MAX_CLIENT_TO_SERVER_PACKET_BYTES) {
                "Client-to-server RWML envelopes are limited to $MAX_CLIENT_TO_SERVER_PACKET_BYTES bytes by the native engine"
            }
        }
        return frame
    }

    /** Builds a control frame advertising currently registered local payload keys. */
    internal fun encodeCapabilitiesFrame(): ByteArray {
        val localRoutes = synchronized(routeLock) { routes.values.toList() }
            .sortedWith(compareBy<Route>({ it.namespace }, { it.version }))
        require(localRoutes.size <= MAX_CAPABILITY_ENTRIES) {
            "Cannot advertise more than $MAX_CAPABILITY_ENTRIES payload keys"
        }
        val bodyBuffer = ByteArrayOutputStream()
        DataOutputStream(bodyBuffer).use { output ->
            output.writeInt(localRoutes.size)
            localRoutes.forEach { route ->
                output.writeUTF(route.namespace)
                output.writeInt(route.version)
                output.writeByte(route.direction.ordinal)
                output.writeByte(if (route.receivers.isEmpty()) CAN_ENCODE else CAN_ENCODE or CAN_RECEIVE)
                output.writeInt(route.maxPayloadBytes)
            }
        }
        val body = bodyBuffer.toByteArray()
        require(body.size <= MAX_CONTROL_BODY_BYTES) { "Payload capability list is too large" }
        return encodeFrame(FRAME_CAPABILITIES) { output ->
            output.writeInt(body.size)
            output.write(body)
        }
    }

    /**
     * Consumes every packet 1000 frame. Returns true when the first capability list from a peer
     * should be answered. Later capability changes are stored without an automatic response; the
     * endpoint that changed its registrations explicitly re-advertises them.
     */
    internal fun dispatchEnvelope(
        payload: ByteArray?,
        connection: Connection?,
        incomingDirection: PacketDirection,
        isServer: Boolean,
    ): Boolean {
        if (payload == null || payload.size !in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_PACKET_BYTES ||
            (isServer && payload.size > MAX_CLIENT_TO_SERVER_PACKET_BYTES)
        ) {
            log.warn("Dropped malformed RWML payload envelope: invalid packet length")
            return false
        }
        return try {
            val input = DataInputStream(ByteArrayInputStream(payload))
            val magic = input.readInt()
            val protocolVersion = input.readUnsignedByte()
            val frameType = input.readUnsignedByte()
            if (magic != ENVELOPE_MAGIC || protocolVersion != ENVELOPE_PROTOCOL_VERSION) {
                log.warn("Dropped unknown RWML payload envelope header")
                return false
            }
            when (frameType) {
                FRAME_CAPABILITIES -> readCapabilities(input, connection, incomingDirection, isServer)
                FRAME_PAYLOAD -> {
                    dispatchPayload(input, connection, incomingDirection)
                    false
                }
                else -> {
                    log.warn("Dropped unknown RWML payload envelope frame type $frameType")
                    false
                }
            }
        } catch (failure: Exception) {
            log.warn("Dropped malformed RWML payload envelope: ${failure.message}")
            false
        }
    }

    /** Clears client/server capability state when the native network session disconnects. */
    internal fun clearNegotiatedCapabilities() {
        synchronized(capabilityLock) { remoteCapabilities.clear() }
    }

    internal fun forgetPeer(connection: Connection) {
        synchronized(capabilityLock) { remoteCapabilities.remove(connection) }
    }

    private fun readCapabilities(
        input: DataInputStream,
        connection: Connection?,
        incomingDirection: PacketDirection,
        isServer: Boolean,
    ): Boolean {
        val expectedDirection = if (isServer) PacketDirection.CLIENT_TO_SERVER else PacketDirection.SERVER_TO_CLIENT
        if (incomingDirection != expectedDirection) {
            log.warn("Dropped payload capability frame arriving in unexpected direction $incomingDirection")
            return false
        }
        val peer = connection ?: run {
            log.warn("Dropped payload capability frame without a client connection")
            return false
        }
        val bodyLength = input.readInt()
        if (bodyLength !in 4..MAX_CONTROL_BODY_BYTES || bodyLength > input.available()) {
            throw IOException("Invalid capability body length $bodyLength")
        }
        val body = ByteArray(bodyLength)
        input.readFully(body)
        requireNoTrailingBytes(input)

        val bodyInput = DataInputStream(ByteArrayInputStream(body))
        val count = bodyInput.readInt()
        if (count !in 0..MAX_CAPABILITY_ENTRIES) throw IOException("Invalid capability count $count")
        val capabilities = LinkedHashMap<PayloadId, PayloadCapability>(count)
        repeat(count) {
            val namespace = bodyInput.readUTF()
            if (!PayloadKey.isValidNamespace(namespace)) throw IOException("Invalid capability namespace")
            val version = bodyInput.readInt()
            if (version !in 1..PayloadKey.MAX_SCHEMA_VERSION) throw IOException("Invalid payload version $version")
            val directionOrdinal = bodyInput.readUnsignedByte()
            val direction = PacketDirection.entries.getOrNull(directionOrdinal)
                ?: throw IOException("Invalid payload direction $directionOrdinal")
            val capabilityFlags = bodyInput.readUnsignedByte()
            val hasEncoder = (capabilityFlags and CAN_ENCODE) != 0
            val hasReceiver = (capabilityFlags and CAN_RECEIVE) != 0
            val unknownFlags = capabilityFlags and (CAN_ENCODE or CAN_RECEIVE).inv()
            if (!hasEncoder || unknownFlags != 0) {
                throw IOException("Invalid payload capability flags $capabilityFlags")
            }
            val maxBytes = bodyInput.readInt()
            if (maxBytes !in 1..PayloadKey.MAX_PAYLOAD_BYTES) throw IOException("Invalid payload size $maxBytes")
            val id = PayloadId(namespace, version)
            if (capabilities.put(
                    id,
                    PayloadCapability(direction, maxBytes, canEncode = hasEncoder, canReceive = hasReceiver),
                ) != null
            ) {
                throw IOException("Duplicate payload capability '$namespace' version $version")
            }
        }
        requireNoTrailingBytes(bodyInput)

        return synchronized(capabilityLock) {
            val previous = remoteCapabilities[peer]
            remoteCapabilities[peer] = capabilities
            previous == null
        }
    }

    private fun dispatchPayload(
        input: DataInputStream,
        connection: Connection?,
        incomingDirection: PacketDirection,
    ) {
        val namespace = input.readUTF()
        if (!PayloadKey.isValidNamespace(namespace)) throw IOException("Invalid payload namespace")
        val version = input.readInt()
        if (version !in 1..PayloadKey.MAX_SCHEMA_VERSION) throw IOException("Invalid payload version $version")
        val length = input.readInt()
        if (length < 0 || length > PayloadKey.MAX_PAYLOAD_BYTES || length > input.available()) {
            throw EOFException("Invalid payload body length $length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        requireNoTrailingBytes(input)

        val id = PayloadId(namespace, version)
        val route = routes[id]
        if (route == null) {
            log.warn("Dropped payload '$namespace' version $version: no local receiver is registered")
            return
        }
        if (length > route.maxPayloadBytes) {
            log.warn("Dropped payload '$namespace': $length bytes exceeds local limit ${route.maxPayloadBytes}")
            return
        }
        if (!route.direction.allows(incomingDirection)) {
            log.warn("Dropped payload '$namespace': direction $incomingDirection is not allowed by ${route.direction}")
            return
        }
        val remoteCapability = connection?.let { synchronized(capabilityLock) { remoteCapabilities[it]?.get(id) } }
        if (remoteCapability == null || !remoteCapability.canEncode ||
            remoteCapability.direction != route.direction ||
            remoteCapability.maxPayloadBytes != route.maxPayloadBytes
        ) {
            log.warn("Dropped payload '$namespace': remote support was not negotiated")
            return
        }

        val receivers = route.receivers.toList()
        if (receivers.isEmpty()) {
            log.warn("Dropped payload '$namespace': no local receiver is registered")
            return
        }

        val value = try {
            route.decode(bytes)
        } catch (failure: Exception) {
            log.error("Failed to decode payload '$namespace' version $version", failure)
            return
        }
        for (receiver in receivers) {
            try {
                receiver(connection, value)
            } catch (failure: Exception) {
                log.error("Payload receiver failed for '$namespace' version $version", failure)
            }
        }
    }

    private fun encodePayloadFrame(key: PayloadKey<*>, payload: ByteArray): ByteArray =
        encodeFrame(FRAME_PAYLOAD) { output ->
            output.writeUTF(key.namespace)
            output.writeInt(key.version)
            output.writeInt(payload.size)
            output.write(payload)
        }

    private fun encodeFrame(frameType: Int, writeBody: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(ENVELOPE_MAGIC)
            output.writeByte(ENVELOPE_PROTOCOL_VERSION)
            output.writeByte(frameType)
            writeBody(output)
        }
        val frame = buffer.toByteArray()
        require(frame.size <= MAX_ENVELOPE_PACKET_BYTES) {
            "RWML payload envelope is ${frame.size} bytes; native packet limit is $MAX_ENVELOPE_PACKET_BYTES"
        }
        return frame
    }

    private fun requireNoTrailingBytes(input: DataInputStream) {
        if (input.available() != 0) throw IOException("Unexpected trailing bytes")
    }

    private data class PayloadId(val namespace: String, val version: Int)

    private data class PayloadCapability(
        val direction: PacketDirection,
        val maxPayloadBytes: Int,
        val canEncode: Boolean,
        val canReceive: Boolean,
    )

    private class Route(
        val namespace: String,
        val version: Int,
        val direction: PacketDirection,
        val maxPayloadBytes: Int,
        val codec: PayloadCodec<*>,
        val decode: (ByteArray) -> Any,
        val encode: (Any) -> ByteArray,
    ) {
        val receivers = CopyOnWriteArrayList<(Connection?, Any) -> Unit>()
        var registrationCount: Int = 0

        fun matches(key: PayloadKey<*>): Boolean =
            namespace == key.namespace && version == key.version && direction == key.direction &&
                maxPayloadBytes == key.maxPayloadBytes && codec === key.codec
    }

    private companion object {
        const val ENVELOPE_MAGIC = 0x52574D4C // "RWML"
        const val ENVELOPE_PROTOCOL_VERSION = 1
        const val FRAME_CAPABILITIES = 1
        const val FRAME_PAYLOAD = 2
        const val CAN_ENCODE = 1
        const val CAN_RECEIVE = 2
        const val MAX_CAPABILITY_ENTRIES = 4_096
        const val MAX_CONTROL_BODY_BYTES = 900_000
        const val MAX_CLIENT_TO_SERVER_PACKET_BYTES = 1_000_000
        const val MAX_ENVELOPE_PACKET_BYTES = 50_000_000
        const val MIN_ENVELOPE_BYTES = 6
    }
}

/** Registers a payload codec/type without an inbound receiver. */
fun <T : Any> Net.registerPayloadType(key: PayloadKey<T>): PayloadTypeHandle =
    payloadRegistry.registerType(key)

/** Registers a payload codec/type without an inbound receiver, owned by [owner]. */
fun <T : Any> Net.registerPayloadType(owner: EventOwner, key: PayloadKey<T>): PayloadTypeHandle =
    payloadRegistry.registerType(owner, key)

/** Registers a namespaced payload receiver and returns a closeable registration. */
fun <T : Any> Net.registerPayloadListener(
    key: PayloadKey<T>,
    receiver: (Connection?, T) -> Unit,
): PayloadListenerHandle = payloadRegistry.register(key, receiver)

/** Registers a namespaced payload receiver that is removed when [owner] closes. */
fun <T : Any> Net.registerPayloadListener(
    owner: EventOwner,
    key: PayloadKey<T>,
    receiver: (Connection?, T) -> Unit,
): PayloadListenerHandle = payloadRegistry.register(owner, key, receiver)

/** Convenience form of [supportsPayload]. */
fun Net.supports(connection: Connection?, key: PayloadKey<*>): Boolean = supportsPayload(connection, key)
