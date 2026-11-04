/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event

import io.github.rwml.api.ExtensionContext

/** Owns closeable subscriptions and resources for one extension or subsystem. */
class EventOwner(context: ExtensionContext? = null) : AutoCloseable {
    private val lock = Any()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false

    init {
        context?.own(this)
    }

    fun <T : AutoCloseable> own(resource: T): T {
        val closeNow = synchronized(lock) {
            if (closed) true else {
                resources.add(resource)
                false
            }
        }
        if (closeNow) {
            try {
                resource.close()
            } catch (failure: Throwable) {
                throw IllegalStateException("Could not close a resource added to a closed EventOwner.", failure)
            }
            throw IllegalStateException("Cannot add a resource to an EventOwner after it has been closed.")
        }
        return resource
    }

    internal fun release(resource: AutoCloseable) {
        synchronized(lock) {
            resources.removeAll { it === resource }
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            resources.asReversed().toList().also { resources.clear() }
        }
        var firstFailure: Throwable? = null
        for (resource in toClose) {
            try {
                resource.close()
            } catch (failure: Throwable) {
                val first = firstFailure
                if (first == null) firstFailure = failure else first.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}
