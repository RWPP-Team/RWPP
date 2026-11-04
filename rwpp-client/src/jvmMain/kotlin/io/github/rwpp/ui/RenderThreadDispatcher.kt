/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/** Scene changes and native GL work are drained only by the thread owning the Slick context. */
internal class RenderThreadDispatcher(private val clock: () -> Long = System::nanoTime) : CoroutineDispatcher() {
    private val owner = Thread.currentThread()
    private val pending = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        pending.add(block)
    }

    fun isOwnerThread(): Boolean = Thread.currentThread() === owner
    fun drain(maxTasks: Int = Int.MAX_VALUE, maxNanos: Long = Long.MAX_VALUE) {
        check(Thread.currentThread() === owner) {
            "Compose must run on its render thread: owner=${owner.name}#${owner.id}, " +
                    "current=${Thread.currentThread().name}#${Thread.currentThread().id}"
        }
        val timed = maxNanos != Long.MAX_VALUE
        val started = if (timed) clock() else 0L
        repeat(maxTasks) {
            (pending.poll() ?: return).run()
            if (timed && clock() - started >= maxNanos) return
        }
    }
}
