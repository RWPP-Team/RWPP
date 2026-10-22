/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net

import com.corrodinggames.rts.gameFramework.network.InputNetStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/** Which side is allowed to send a registered extension packet. */
enum class PacketDirection {
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT,
    BIDIRECTIONAL,
    ;

    internal fun allows(incoming: PacketDirection): Boolean =
        this == BIDIRECTIONAL || this == incoming
}

/**
 * A typed decoder for the game's extension packet body.
 *
 * Receivers run on the thread used by the native packet processor. For extension packet IDs this is
 * the network receive worker, so implementations should decode and validate only. Game state changes
 * must be queued onto the game thread explicitly.
 */
interface PacketCodec<T : Packet> {
    fun decode(payload: ByteArray): T

    fun encode(packet: T): ByteArray = packet.toBytes()
}

/**
 * Defines one extension packet on the existing native integer packet channel.
 *
 * The namespace identifies the owner locally; it is not transmitted on the wire. The numeric ID must
 * therefore be agreed by both peers and is globally unique among registered extension packet types.
 */
class PacketKey<T : Packet>(
    val namespace: String,
    val id: Int,
    val direction: PacketDirection,
    val codec: PacketCodec<T>,
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    init {
        require(
            namespace.isNotBlank() && namespace.length <= 96 && namespace.all {
                it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
            },
        ) { "Packet namespace must be a lowercase namespaced identifier: $namespace" }
        require(id in FIRST_EXTENSION_PACKET_ID..LAST_EXTENSION_PACKET_ID) {
            "Extension packet ID must be in $FIRST_EXTENSION_PACKET_ID..$LAST_EXTENSION_PACKET_ID: $id"
        }
        require(!InternalPacketType.isNative(id)) { "Packet ID $id is reserved by the game" }
        require(maxPayloadBytes in 1..MAX_PAYLOAD_BYTES) {
            "maxPayloadBytes must be in 1..$MAX_PAYLOAD_BYTES: $maxPayloadBytes"
        }
    }

    companion object {
        const val FIRST_EXTENSION_PACKET_ID = 500
        const val LAST_EXTENSION_PACKET_ID = PayloadKey.PAYLOAD_ENVELOPE_PACKET_ID - 1
        const val DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024
        const val MAX_PAYLOAD_BYTES = 50_000_000
    }
}

/** Builds a codec for the native [Packet.readPacket] / [Packet.writePacket] stream format. */
fun <T : Packet> nativePacketCodec(create: () -> T): PacketCodec<T> =
    object : PacketCodec<T> {
        override fun decode(payload: ByteArray): T = create().also { packet ->
            packet.readPacket(BoundedInputNetStream(payload))
        }
    }

/** Prevents the native stream reader from allocating a declared byte-array length past its body. */
private class BoundedInputNetStream(payload: ByteArray) :
    InputNetStream(DataInputStream(ByteArrayInputStream(payload))) {
    override fun readByteArray(): ByteArray {
        val length = readInt()
        if (length < 0 || length > PacketKey.MAX_PAYLOAD_BYTES) {
            throw IOException("Invalid packet byte-array length: $length")
        }
        val input = getInputStream() as? DataInputStream
            ?: throw IOException("Native packet reader is not backed by a data stream")
        val remaining = input.available()
        if (length > remaining) {
            throw EOFException("Packet byte-array declares $length bytes, but only $remaining remain")
        }
        return ByteArray(length).also { input.readFully(it) }
    }
}
