/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwpp.desktop.OffscreenComposeRenderer
import org.lwjgl.opengl.Display
import org.newdawn.slick.InputListener
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

/**
 * Runs Compose scene work on AWT's UI thread while borrowing Slick's existing GL context.
 * The bridge is created by Slick's render thread and never creates or reflects into a GL context.
 */
internal class SlickAwtGlContextBridge {
    private val ownerThread = Thread.currentThread()
    private val borrowedContext = ThreadLocal<Boolean>()

    fun <T> call(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            check(borrowedContext.get() == true) {
                "The AWT thread may access the Compose scene only while Slick's GL context is borrowed"
            }
            return action()
        }

        check(Thread.currentThread() === ownerThread) {
            "Slick GL context borrowing must be initiated by its owner thread"
        }

        var result: Any? = null
        var failure: Throwable? = null
        var interrupted = false
        var contextReleased = false
        val awtCallbackFinished = CountDownLatch(1)

        try {
            check(Display.isCurrent()) { "Slick's Display context is not current on its owner thread" }
            Display.releaseContext()
            contextReleased = true

            try {
                SwingUtilities.invokeAndWait {
                    var contextCurrent = false
                    try {
                        Display.makeCurrent()
                        contextCurrent = true
                        check(Display.isCurrent()) { "Slick's GL context was not current on AWT" }
                        borrowedContext.set(true)
                        result = action()
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        borrowedContext.remove()
                        if (contextCurrent) {
                            try {
                                Display.releaseContext()
                            } catch (error: Throwable) {
                                failure = combineFailures(failure, error)
                            }
                        }
                        awtCallbackFinished.countDown()
                    }
                }
            } catch (error: InterruptedException) {
                interrupted = true
                // invokeAndWait may already have queued its callback. Do not restore the GL
                // context to Slick until that callback has released it on the AWT thread.
                while (true) {
                    try {
                        awtCallbackFinished.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                failure = combineFailures(failure, error)
            } catch (error: InvocationTargetException) {
                failure = combineFailures(failure, error.targetException ?: error)
            }
        } catch (error: Throwable) {
            failure = combineFailures(failure, error)
        } finally {
            if (contextReleased) {
                try {
                    Display.makeCurrent()
                    check(Display.isCurrent()) { "Slick's Display context was not restored to its owner thread" }
                } catch (error: Throwable) {
                    failure = combineFailures(failure, error)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun combineFailures(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        current.addSuppressed(next)
        return current
    }
}

/** Slick dispatch is synchronous; consuming the native event on AWT preserves hit testing. */
internal class SlickAwtComposeInputListener(
    private val renderer: OffscreenComposeRenderer,
    private val bridge: SlickAwtGlContextBridge
) : InputListener by renderer {
    override fun mouseMoved(oldx: Int, oldy: Int, newx: Int, newy: Int) = bridge.call {
        renderer.mouseMoved(oldx, oldy, newx, newy)
    }

    override fun mouseDragged(oldx: Int, oldy: Int, newx: Int, newy: Int) = bridge.call {
        renderer.mouseDragged(oldx, oldy, newx, newy)
    }

    override fun mousePressed(button: Int, x: Int, y: Int) = bridge.call {
        renderer.mousePressed(button, x, y)
        DesktopComposeRuntime.requestFrame()
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) = bridge.call {
        renderer.mouseReleased(button, x, y)
        DesktopComposeRuntime.requestFrame()
    }

    override fun mouseWheelMoved(change: Int) = bridge.call {
        renderer.mouseWheelMoved(change)
        DesktopComposeRuntime.requestFrame()
    }

    override fun keyPressed(key: Int, c: Char) = bridge.call {
        renderer.keyPressed(key, c)
        DesktopComposeRuntime.requestFrame()
    }

    override fun keyReleased(key: Int, c: Char) = bridge.call {
        renderer.keyReleased(key, c)
        DesktopComposeRuntime.requestFrame()
    }
}
