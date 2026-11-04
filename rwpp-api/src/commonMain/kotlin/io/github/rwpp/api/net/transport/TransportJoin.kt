/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一次客户端加入。
 *
 * 流程对两种传输一致：先发握手首帧，收到欢迎帧后派生会话密钥，此后每条消息都经加密信封进出。传输实现由调用方
 * 给出，因此同一段代码既能走 KCP 也能走同端口 TCP。
 *
 * 回调在传输线程上执行，实现方应把工作交给自己的有界队列。
 */
class TransportJoin(
    private val protocol: HandshakeProtocol,
    private val credential: PlayerCredential,
    private val transport: ClientTransport,
) {

    private val lock = ReentrantLock()
    private var link: ClientLink? = null
    private var channel: SecureChannel? = null
    private var pending: Handshake.Client? = null
    private var closed = false
    private var failed = false

    /** 收到的消息，已解密。 */
    var onMessage: (ByteArray) -> Unit = {}

    /** 加入完成，可以开始发送。 */
    var onJoined: (SecureChannel) -> Unit = {}

    /** 加入失败或连接断开。只调用一次。 */
    var onFailure: (String) -> Unit = {}

    /** 建立连接并开始握手。 */
    fun start() {
        val handshake = lock.withLock {
            if (closed) null else Handshake.Client(protocol, credential)
        } ?: return
        val connection = transport.connect(object : ClientSink {
            override fun message(payload: ByteArray) {
                receive(payload)
            }

            override fun failure(reason: String) {
                fail(reason)
            }
        })
        lock.withLock {
            link = connection
            pending = handshake
        }
        connection.send(handshake.login)
    }

    /** 发送一条消息；尚未完成握手或连接已关闭时返回 false。 */
    fun send(plaintext: ByteArray): Boolean {
        val state = lock.withLock { channel to link }
        val active = state.first ?: return false
        return state.second?.send(active.seal(plaintext)) ?: false
    }

    fun close() {
        val connection = lock.withLock {
            if (closed) {
                null
            } else {
                closed = true
                link.also { link = null }
            }
        }
        connection?.close()
        transport.close()
    }

    private fun receive(payload: ByteArray) {
        var joined: SecureChannel? = null
        var message: ByteArray? = null
        var failure: String? = null
        lock.withLock {
            if (closed) return
            val active = channel
            if (active == null) {
                val handshake = pending ?: return
                pending = null
                val established = runCatching { handshake.finish(payload) }
                if (established.isSuccess) {
                    val opened = established.getOrThrow()
                    channel = opened
                    joined = opened
                } else {
                    failure = established.exceptionOrNull()?.message ?: DEFAULT_FAILURE
                }
            } else {
                val opened = runCatching { active.open(payload) }
                if (opened.isSuccess) {
                    message = opened.getOrThrow()
                } else {
                    failure = opened.exceptionOrNull()?.message ?: DEFAULT_FAILURE
                }
            }
        }
        val reason = failure
        if (reason != null) {
            fail(reason)
            return
        }
        joined?.let(onJoined)
        message?.let(onMessage)
    }

    private fun fail(reason: String) {
        val notify = lock.withLock {
            if (failed || closed) {
                false
            } else {
                failed = true
                true
            }
        }
        if (notify) onFailure(reason)
    }

    private companion object {
        const val DEFAULT_FAILURE = "The session could not be established"
    }
}
