/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net.transport

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 每个来源地址的连接配额。
 *
 * KCP 只有在读到第一段报文时才知道对端存在，而段报文可以是伪造的。这里在分配会话之前计数，使同一来源无法用
 * 随机端口堆满服务端的会话表；合法连接的断开由调用方归还。
 */
class AdmissionLimit(private val maximumPeers: Int) {

    private val perAddress = ConcurrentHashMap<InetSocketAddress, AtomicInteger>()
    private val total = AtomicInteger()

    /** 登记一个新来源地址；超过配额时返回 false。 */
    fun tryAcquire(address: InetSocketAddress): Boolean {
        if (total.incrementAndGet() > maximumPeers) {
            total.decrementAndGet()
            return false
        }
        perAddress.computeIfAbsent(address) { AtomicInteger() }.incrementAndGet()
        return true
    }

    /** 归还一个来源地址。 */
    fun release(address: InetSocketAddress) {
        val counter = perAddress[address] ?: return
        if (counter.decrementAndGet() <= 0) {
            perAddress.remove(address, counter)
        }
        total.decrementAndGet()
    }

    /** 当前占用数量。 */
    val size: Int get() = total.get()
}
