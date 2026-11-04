/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net

import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.logging.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private val log = logger()

/** Outcome of looking up a packet in the typed extension registry. */
enum class PacketDispatchResult {
    /** No typed packet is registered for this native integer ID. */
    UNREGISTERED,

    /** A receiver allowed the native engine to continue handling the packet. */
    PASS,

    /** A receiver consumed the packet, or the registered packet failed validation. */
    CONSUME,
}

/** A closeable receiver registration, suitable for [io.github.rwml.api.ExtensionContext.own]. */
class PacketListenerHandle internal constructor(
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
 * Thread-safe receiver registry for native extension packet IDs.
 *
 * The native wire header contains only the integer ID, so each ID may have one namespace and codec.
 * A key can have multiple receivers; reusing the same key and codec lets them share one decode.
 * A receiver returns `true` to consume the packet and stop native handling; `false` lets native
 * handling continue after all receivers have run.
 */
class PacketRegistry {
    private val routes = ConcurrentHashMap<Int, Route>()
    private val lock = Any()

    fun <T : Packet> register(
        key: PacketKey<T>,
        receiver: (Connection?, T) -> Boolean,
    ): PacketListenerHandle = registerInternal(null, key, receiver)

    private fun <T : Packet> registerInternal(
        owner: EventOwner?,
        key: PacketKey<T>,
        receiver: (Connection?, T) -> Boolean,
    ): PacketListenerHandle {
        @Suppress("UNCHECKED_CAST")
        val erasedReceiver: (Connection?, Packet) -> Boolean = { connection, packet ->
            receiver(connection, packet as T)
        }

        val route = synchronized(lock) {
            val existing = routes[key.id]
            val selected = existing ?: Route(
                namespace = key.namespace,
                direction = key.direction,
                maxPayloadBytes = key.maxPayloadBytes,
                decode = { payload -> key.codec.decode(payload) },
                encode = { packet ->
                    @Suppress("UNCHECKED_CAST")
                    key.codec.encode(packet as T)
                },
                codecIdentity = key.codec,
            )
            if (existing != null &&
                (existing.namespace != key.namespace ||
                        existing.direction != key.direction ||
                        existing.maxPayloadBytes != key.maxPayloadBytes ||
                        existing.codecIdentity !== key.codec)
            ) {
                throw IllegalArgumentException(
                    "Packet ID ${key.id} is already registered by namespace '${existing.namespace}'. " +
                            "Reuse its PacketKey and PacketCodec for additional receivers.",
                )
            }
            selected.receivers.add(erasedReceiver)
            if (existing == null) routes[key.id] = selected
            selected
        }

        return PacketListenerHandle(owner) {
            synchronized(lock) {
                route.receivers.remove(erasedReceiver)
                if (route.receivers.isEmpty()) routes.remove(key.id, route)
            }
        }
    }

    /** Registers a receiver and closes it when [owner] closes. */
    fun <T : Packet> register(
        owner: EventOwner,
        key: PacketKey<T>,
        receiver: (Connection?, T) -> Boolean,
    ): PacketListenerHandle = owner.own(registerInternal(owner, key, receiver))

    /** Dispatches a received native packet without changing its native thread. */
    fun dispatch(
        id: Int,
        payload: ByteArray?,
        connection: Connection?,
        incomingDirection: PacketDirection,
    ): PacketDispatchResult {
        val route = routes[id] ?: return PacketDispatchResult.UNREGISTERED
        val receivers = route.receivers.toList()
        if (receivers.isEmpty()) return PacketDispatchResult.UNREGISTERED
        val bytes = payload ?: ByteArray(0)
        if (bytes.size > route.maxPayloadBytes) {
            log.warn(
                "Dropped extension packet $id from '${route.namespace}': payload is ${bytes.size} bytes, " +
                        "limit is ${route.maxPayloadBytes}.",
            )
            return PacketDispatchResult.CONSUME
        }
        if (!route.direction.allows(incomingDirection)) {
            log.warn(
                "Dropped extension packet $id from '${route.namespace}': direction $incomingDirection " +
                        "is not allowed by ${route.direction}.",
            )
            return PacketDispatchResult.CONSUME
        }

        val packet = try {
            route.decode(bytes)
        } catch (failure: Exception) {
            log.error("Failed to decode extension packet $id from '${route.namespace}'.", failure)
            return PacketDispatchResult.CONSUME
        }
        if (packet.type != id) {
            log.warn(
                "Dropped extension packet $id from '${route.namespace}': its codec decoded packet type ${packet.type}.",
            )
            return PacketDispatchResult.CONSUME
        }

        var consumed = false
        for (receiver in receivers) {
            try {
                if (receiver(connection, packet)) consumed = true
            } catch (failure: Exception) {
                log.error("Extension packet receiver failed for ID $id from '${route.namespace}'.", failure)
                // Do not let a malformed or partially handled extension packet fall through to
                // native handling, but still deliver it to other receivers for this registered key.
                consumed = true
            }
        }
        return if (consumed) PacketDispatchResult.CONSUME else PacketDispatchResult.PASS
    }

    /** Validates a locally registered packet before it is sent in the given direction. */
    fun validateOutgoing(id: Int, payloadBytes: Int, outgoingDirection: PacketDirection) {
        val route = routes[id] ?: return
        require(route.direction.allows(outgoingDirection)) {
            "Packet $id from '${route.namespace}' cannot be sent in direction $outgoingDirection"
        }
        require(payloadBytes <= route.maxPayloadBytes) {
            "Packet $id from '${route.namespace}' is $payloadBytes bytes; limit is ${route.maxPayloadBytes}"
        }
    }

    /** Encodes a packet with its registered codec and validates direction and maximum size. */
    fun encodeOutgoing(packet: Packet, outgoingDirection: PacketDirection): ByteArray {
        val route = routes[packet.type] ?: return packet.toBytes()
        val payload = try {
            route.encode(packet)
        } catch (failure: Exception) {
            throw IllegalArgumentException(
                "Packet codec from '${route.namespace}' could not encode type ${packet.type}",
                failure,
            )
        }
        validateOutgoing(packet.type, payload.size, outgoingDirection)
        return payload
    }

    private class Route(
        val namespace: String,
        val direction: PacketDirection,
        val maxPayloadBytes: Int,
        val decode: (ByteArray) -> Packet,
        val encode: (Packet) -> ByteArray,
        val codecIdentity: PacketCodec<*>,
    ) {
        val receivers = CopyOnWriteArrayList<(Connection?, Packet) -> Boolean>()
    }
}
