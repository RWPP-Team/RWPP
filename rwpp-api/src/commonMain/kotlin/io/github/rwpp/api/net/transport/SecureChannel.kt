/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net.transport

/** 一条连接的预共享密钥与分配到的槽位。 */
data class PlayerCredential(val playerId: Int, val team: Int, val key: ByteArray) {
    init {
        require(playerId >= 0 && team >= 0 && key.size == 32)
    }
}

/** 服务端接受一次握手的结果。 */
data class ServerHandshake(val credential: PlayerCredential, val welcome: ByteArray, val channel: SecureChannel)

/**
 * 握手参数。
 *
 * 传输层只负责把字节送到对端，身份与保密由这一层提供。魔数、版本与 HKDF 标签由使用方给出：线协议属于使用这份
 * 传输的扩展，传输层不替它决定。
 *
 * @param magic 每条握手帧与每个加密帧开头的魔数
 * @param version 协议版本字节，参与帧头校验
 * @param sessionLabel HKDF 的用途标签，使同一把密钥在不同协议下派生不同的会话密钥
 * @param maximumPlaintext 单条消息的明文上限，也是加密帧信封的校验边界
 */
data class HandshakeProtocol(
    val magic: Int,
    val version: Byte,
    val sessionLabel: String,
    val maximumPlaintext: Int,
) {
    init {
        require(sessionLabel.isNotEmpty())
        require(maximumPlaintext in 1..1024 * 1024)
    }
}

/**
 * 密钥交换与帧加密。
 *
 * KCP 只提供可靠性；这一层提供身份验证、保密与防重放。每个方向用一把密钥，序号参与 AAD，收到重复或跳号的帧会被拒绝。
 *
 * 摘要与分组加密由平台提供，共享源集只描述报文与派生流程，因此同一份实现同时服务桌面与安卓。
 */
object Handshake {

    private const val LOGIN: Byte = 1
    private const val WELCOME: Byte = 2

    /** 握手帧长度：魔数、版本、类型、玩家号与 32 字节随机数，再加 32 字节 HMAC。 */
    private const val HANDSHAKE_BYTES = 74
    private const val HEADER_BYTES = 42
    private const val KEY_BYTES = 32

    /** 客户端一侧的握手状态。 */
    class Client(private val protocol: HandshakeProtocol, private val credential: PlayerCredential) {

        private val nonce = secureRandomBytes(KEY_BYTES)

        /** 首帧：玩家号加客户端随机数，附 HMAC。 */
        val login: ByteArray = header(protocol, LOGIN, credential.playerId, nonce).let {
            it + hmacSha256(credential.key, it)
        }

        /**
         * 校验收到的欢迎帧并派生会话密钥。
         *
         * @throws IllegalArgumentException 欢迎帧不属于本端本次握手
         */
        fun finish(welcome: ByteArray): SecureChannel {
            require(welcome.size == HANDSHAKE_BYTES) { "Unexpected welcome length: ${welcome.size}" }
            val body = welcome.copyOfRange(0, HEADER_BYTES)
            require(body.readInt(0) == protocol.magic && body[4] == protocol.version && body[5] == WELCOME) {
                "The welcome frame does not match this connection"
            }
            require(body.readInt(6) == credential.playerId) { "The welcome frame names another player" }
            val serverNonce = body.copyOfRange(10, 10 + KEY_BYTES)
            require(constantTimeEquals(hmacSha256(credential.key, login + body), welcome.copyOfRange(HEADER_BYTES, HANDSHAKE_BYTES))) {
                "The welcome frame failed authentication"
            }
            return derive(protocol, credential.key, nonce, serverNonce, server = false)
        }
    }

    /**
     * 服务端一侧的握手。
     *
     * @param lookup 按玩家号找回凭据；未知玩家返回 null
     */
    fun accept(
        protocol: HandshakeProtocol,
        login: ByteArray,
        lookup: (Int) -> PlayerCredential?,
    ): ServerHandshake {
        require(login.size == HANDSHAKE_BYTES) { "Unexpected login length: ${login.size}" }
        val body = login.copyOfRange(0, HEADER_BYTES)
        require(body.readInt(0) == protocol.magic && body[4] == protocol.version && body[5] == LOGIN) {
            "The login frame does not match this protocol"
        }
        val credential = lookup(body.readInt(6)) ?: throw IllegalArgumentException("The player is not in this room")
        val clientNonce = body.copyOfRange(10, 10 + KEY_BYTES)
        require(constantTimeEquals(hmacSha256(credential.key, body), login.copyOfRange(HEADER_BYTES, HANDSHAKE_BYTES))) {
            "The login frame failed authentication"
        }
        val serverNonce = secureRandomBytes(KEY_BYTES)
        val welcomeBody = header(protocol, WELCOME, credential.playerId, serverNonce)
        return ServerHandshake(
            credential,
            welcomeBody + hmacSha256(credential.key, login + welcomeBody),
            derive(protocol, credential.key, clientNonce, serverNonce, server = true),
        )
    }

    private fun header(protocol: HandshakeProtocol, kind: Byte, player: Int, nonce: ByteArray): ByteArray {
        val body = ByteArray(HEADER_BYTES)
        body.writeInt(0, protocol.magic)
        body[4] = protocol.version
        body[5] = kind
        body.writeInt(6, player)
        nonce.copyInto(body, 10, 0, KEY_BYTES)
        return body
    }

    /**
     * HKDF-SHA256，按用途标签与其他使用同一把预共享密钥的场合区分。
     *
     * 使用方自行协商密钥材料时（例如先做一次公钥交换，再把共享密钥当作输入），直接调用这里得到会话通道。
     */
    fun derive(
        protocol: HandshakeProtocol,
        key: ByteArray,
        client: ByteArray,
        serverNonce: ByteArray,
        server: Boolean,
    ): SecureChannel {
        val prk = hmacSha256(key, client + serverNonce)
        val info = protocol.sessionLabel.encodeToByteArray()
        var previous = ByteArray(0)
        val material = ArrayList<Byte>(96)
        for (round in 1..3) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(round.toByte()))
            previous.forEach(material::add)
        }
        val bytes = material.toByteArray()
        val clientToServer = bytes.copyOfRange(0, 32)
        val serverToClient = bytes.copyOfRange(32, 64)
        val clientPrefix = bytes.copyOfRange(64, 68)
        val serverPrefix = bytes.copyOfRange(68, 72)
        return if (server) {
            SecureChannel(protocol, serverToClient, clientToServer, serverPrefix, clientPrefix)
        } else {
            SecureChannel(protocol, clientToServer, serverToClient, clientPrefix, serverPrefix)
        }
    }
}

/**
 * 一个方向的发送密钥、反方向的接收密钥，以及各自的 nonce 前缀。
 *
 * 一个线程独占这一通道，与房间的单线程模拟一致。
 */
class SecureChannel internal constructor(
    private val protocol: HandshakeProtocol,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendPrefix: ByteArray,
    private val receivePrefix: ByteArray,
) {

    private var sendSequence = 0L
    private var receiveSequence = 0L

    /** 加密一条消息。 */
    fun seal(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= protocol.maximumPlaintext && sendSequence < Long.MAX_VALUE)
        val sequence = sendSequence++
        val header = ByteArray(ENVELOPE_HEADER_BYTES)
        header.writeInt(0, protocol.magic)
        header[4] = protocol.version
        header[5] = SEALED
        header.writeLong(6, sequence)
        return header + aesGcmSeal(sendKey, nonce(sendPrefix, sequence), header, plaintext)
    }

    /**
     * 解密并验证一条消息。
     *
     * @throws IllegalArgumentException 帧头不符、序号不是下一个期望值或认证失败
     */
    fun open(packet: ByteArray): ByteArray {
        val tagBytes = 16
        require(packet.size in (ENVELOPE_HEADER_BYTES + tagBytes)..(protocol.maximumPlaintext + ENVELOPE_HEADER_BYTES + tagBytes))
        val header = packet.copyOfRange(0, ENVELOPE_HEADER_BYTES)
        require(header.readInt(0) == protocol.magic && header[4] == protocol.version && header[5] == SEALED)
        val sequence = header.readLong(6)
        require(sequence == receiveSequence && sequence < Long.MAX_VALUE) { "Out-of-order message" }
        val plain = aesGcmOpen(
            receiveKey,
            nonce(receivePrefix, sequence),
            header,
            packet.copyOfRange(ENVELOPE_HEADER_BYTES, packet.size),
        )
        receiveSequence++
        return plain
    }

    private fun nonce(prefix: ByteArray, sequence: Long): ByteArray {
        val nonce = ByteArray(12)
        prefix.copyInto(nonce, 0, 0, 4)
        nonce.writeLong(4, sequence)
        return nonce
    }

    private companion object {
        /** 帧信封头：魔数、版本、类型与 8 字节序号。 */
        const val ENVELOPE_HEADER_BYTES = 14
        const val SEALED: Byte = 3
    }
}

internal fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.writeInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.readLong(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    }
    return value
}

internal fun ByteArray.writeLong(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}
