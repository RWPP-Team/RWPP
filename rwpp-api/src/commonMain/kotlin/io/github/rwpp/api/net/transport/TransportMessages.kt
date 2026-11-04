/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

/**
 * 传输层报告失败时使用的文案。
 *
 * 传输层本身不认识产品的翻译机制，默认给出英文；使用方可以在构造传输时传入本地化实现。
 */
interface TransportMessages {

    /** 收到的消息超过传输层的上限。 */
    fun invalidPacketSize(): String

    /** 接收队列已满，传输层因此断开连接。 */
    fun inboundQueueFull(): String

    /**
     * 传输层内部报告了失败。
     *
     * @param transport 传输名称，用于区分 UDP 与 TCP
     */
    fun transportFailure(transport: String, cause: Throwable?): String

    /** 连接已关闭。 */
    fun connectionClosed(transport: String): String

    companion object {

        /** 英文文案。 */
        val DEFAULT: TransportMessages = object : TransportMessages {
            override fun invalidPacketSize(): String =
                "The server sent a message larger than the transport allows"

            override fun inboundQueueFull(): String = "The inbound message queue is full"

            override fun transportFailure(transport: String, cause: Throwable?): String =
                "The $transport transport reported a failure: ${cause?.message}"

            override fun connectionClosed(transport: String): String = "The $transport connection was closed"
        }
    }
}
