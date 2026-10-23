/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net.transport

/**
 * 传输层参数。服务端两种传输共用同一份设置，因此 TCP 与 KCP 监听同一端口时行为一致。
 *
 * CRC、前向纠错与流模式一律关闭：这些功能会在实时游戏帧上引入额外延迟，而且应用层的加密信封已经保证完整性。
 */
data class TransportConfig(
    val bindHost: String = "127.0.0.1",
    val port: Int = 5123,
    val maxPeers: Int = 128,
    val maxPayloadBytes: Int = 60 * 1024,
    val mtu: Int = 1200,
    val conv: Int = 0,
    val intervalMillis: Int = 10,
    val idleTimeoutMillis: Long = 15_000,
    val sendWindow: Int = 128,
    val receiveWindow: Int = 128,
    val writeQueueMessages: Int = 128,
    val readQueueDatagrams: Int = 128,
    val messageThreads: Int = defaultMessageThreads(),
    /** TCP 通道单帧上限。客户端输入通道由原版协议限制在 2 KiB。 */
    val maxTcpPayloadBytes: Int = 2048,
) {
    init {
        require(bindHost.isNotBlank())
        require(port in 0..65535)
        require(maxPeers in 1..65535)
        require(mtu in 576..1400)
        require(maxPayloadBytes in 1..60 * 1024)
        require(intervalMillis in 10..100)
        require(idleTimeoutMillis in 100..300_000)
        require(sendWindow in 1..4096)
        require(receiveWindow in maximumFragments..4096)
        require(maximumFragments <= 255)
        require(writeQueueMessages in 1..4096)
        require(readQueueDatagrams in 1..4096)
        require(messageThreads in 1..64)
        require(maxTcpPayloadBytes in 1..1024 * 1024)
    }

    /** 一条消息最多被切成多少个 KCP 分片；KCP 的分片计数只有一字节。 */
    val maximumFragments: Int get() = (maxPayloadBytes + mtu - 25) / (mtu - 24)
}

/** 当前平台的可用处理器数量，用于给 KCP 的消息线程池定默认值。 */
fun defaultMessageThreads(): Int = minOf(8, Runtime.getRuntime().availableProcessors())
