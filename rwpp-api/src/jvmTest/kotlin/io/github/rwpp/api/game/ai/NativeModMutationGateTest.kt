/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受  RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeModMutationGateTest {
    @Test
    fun modReloadAndAiMixRebuildShareOneCriticalSection() {
        val reloadEntered = CountDownLatch(1)
        val releaseReload = CountDownLatch(1)
        val rebuildEntered = CountDownLatch(1)
        val reloadFailure = AtomicReference<Throwable?>()
        val reload = Thread {
            runCatching {
                NativeModMutationGate.withLock {
                    reloadEntered.countDown()
                    check(releaseReload.await(5, TimeUnit.SECONDS))
                }
            }.onFailure(reloadFailure::set)
        }
        val rebuild = Thread {
            if (reloadEntered.await(5, TimeUnit.SECONDS)) {
                NativeModMutationGate.withLock { rebuildEntered.countDown() }
            }
        }

        reload.start()
        assertTrue(reloadEntered.await(5, TimeUnit.SECONDS))
        rebuild.start()
        try {
            assertFalse(rebuildEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseReload.countDown()
        }

        assertTrue(rebuildEntered.await(5, TimeUnit.SECONDS))
        reload.join(5_000)
        rebuild.join(5_000)
        assertFalse(reload.isAlive)
        assertFalse(rebuild.isAlive)
        assertNull(reloadFailure.get())
    }

    @Test
    fun actionSnapshotCanBeTraversedWhileNativeListChanges() {
        val actions = ArrayList<Int>()
        val stopWriter = AtomicBoolean(false)
        val writerFailure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                var action = 0
                while (!stopWriter.get()) {
                    actions.add(action++)
                    if (actions.size > 32) actions.removeAt(0)
                }
            } catch (failure: Throwable) {
                writerFailure.set(failure)
            }
        }

        writer.start()
        try {
            repeat(2_000) {
                NativeModMutationGate.snapshot(actions).forEach { it.hashCode() }
            }
        } finally {
            stopWriter.set(true)
            writer.join(5_000)
        }

        assertFalse(writer.isAlive)
        assertNull(writerFailure.get())
    }
}
