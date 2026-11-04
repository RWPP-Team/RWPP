/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

/**
 * 传输端点。
 *
 * 契约放在共享源集，因此这里不出现平台套接字类型。服务端与客户端各自把它换成本平台的地址对象。
 */
data class TransportAddress(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * 一条已建立的传输连接。
 *
 * 回调在传输自己的工作线程上串行执行，因此同一个连接上的消息不会被并发处理。回调不得持有传入的字节数组：
 * 传输层可能在回调返回后复用自己的缓冲区，需要保留时请自行复制。
 */
interface TransportPeer {

    /** 本次服务生命周期内唯一的连接标识。 */
    val id: Long

    /** 对端地址；无法取得时为 null。 */
    val remoteHost: String? get() = null

    /**
     * 尽力发送一条消息。
     *
     * @return 已交给传输层时为 true；连接已关闭、队列已满或消息超过上限时为 false
     */
    fun trySend(payload: ByteArray): Boolean

    /** 关闭连接。可重复调用。 */
    fun close()
}

/** 服务端接收的连接事件。实现应快速返回，重活交给自己的有界队列。 */
interface TransportListener {

    /** 连接已通过传输层准入。此时尚未验证对端身份。 */
    fun connected(peer: TransportPeer)

    /** 收到一条完整消息。 */
    fun received(peer: TransportPeer, payload: ByteArray)

    /** 连接已失效。此后不会再收到该对端的任何回调。 */
    fun disconnected(peer: TransportPeer)
}

/** 监听一个端口并为每条连接产生 [TransportPeer]。 */
interface ServerTransport : AutoCloseable {

    /**
     * 开始监听。
     *
     * @throws RuntimeException 端口无法绑定时抛出；此时传输层已释放自己申请的资源
     */
    fun start(listener: TransportListener)

    /** 绑定成功后对外发布的地址；尚未启动或已关闭时为 null。 */
    val localAddress: TransportAddress? get() = null
}

/** 客户端连接：一条消息队列承载收到的完整消息。 */
interface ClientLink {

    /** @return 消息已交给传输层时为 true */
    fun send(payload: ByteArray): Boolean

    fun close()
}

/**
 * 客户端收到的结果。
 *
 * 连接建立后传输层只通过这里交付消息与失败原因，客户端不需要提供自己的并发队列类型。
 */
interface ClientSink {

    /** 收到一条完整消息。实现不得阻塞传输线程。 */
    fun message(payload: ByteArray)

    /** 连接失败或已断开。传输层只报告一次。 */
    fun failure(reason: String)
}

/** 客户端传输。一次客户端生命周期只创建一条，重连不累积线程。 */
interface ClientTransport : AutoCloseable {

    /** 连接服务端，收到的消息进入 [sink]。 */
    fun connect(sink: ClientSink): ClientLink
}

/**
 * 端口上首帧的通用应答。
 *
 * 原版游戏用同一个端口回答“这台服务器是什么”的查询，而该查询与房间协议共用一条连接。传输层不认识它的内容，
 * 只在建立会话之前把首帧交给这里；实现返回应答字节时，传输层写完就关闭连接，不把它算作一个对端。
 */
fun interface TransportBanner {

    /**
     * @param frame 对端发来的第一帧
     * @return 要写回的字节；不是本端回答的查询时返回 null，连接照常进入房间协议
     */
    fun answer(frame: ByteArray): ByteArray?
}
