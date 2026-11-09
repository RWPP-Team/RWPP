/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.coroutines

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Game operations run on one drain owner at a time. Android uses the main looper while the
 * native loop is idle, then hands ownership to the native frame thread while it is active. A
 * lightweight main-looper monitor observes when the loop stops and reclaims the queue. The
 * handoff is serialized, but this is not fixed thread affinity: a coroutine may resume on the
 * other owner after a lifecycle transition. Frame-bound native state should be accessed while
 * the native loop owns the dispatcher.
 */
object GameMainDispatcher : CoroutineDispatcher() {
    private val taskQueue = ConcurrentLinkedQueue<Runnable>()
    private val updateTasks = CopyOnWriteArrayList<Runnable>()
    private val drainLock = Any()
    private var nativeLoopActive = false
    private var engineInitialized = false
    private var nativeThread: Thread? = null
    private var handoffThread: Thread? = null
    private var lastNativeFrameNanos = 0L

    @Volatile
    private var wakeThread: Thread? = null
    private val bridgeFrames = object : ThreadLocal<NativeBridgeThreadState>() {
        override fun initialValue() = NativeBridgeThreadState()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.add(block)
        val owner = wakeThread
        if (owner != null) LockSupport.unpark(owner)
    }

    /**
     * Drains work on Android's main looper while the native game loop is idle. The pump remains
     * scheduled while the game is active so it can detect a return to the launcher, but it does
     * not drain the queue while native frames own it.
     */
    internal fun drainPlatformPump(): Boolean = synchronized(drainLock) {
        if (refreshNativeLoopStateLocked()) {
            PlatformGameMainDispatcherPump.stop()
            return@synchronized true
        }
        drainTasksLocked()
        false
    }

    /** Starts Android's idle pump; it remains scheduled to detect later native-loop transitions. */
    internal fun startPump() {
        synchronized(drainLock) {
            if (!refreshNativeLoopStateLocked()) PlatformGameMainDispatcherPump.start()
        }
    }

    /** Reports whether the native loop owns game-thread work at this moment. */
    internal fun isNativeLoopActive(): Boolean = synchronized(drainLock) {
        refreshNativeLoopStateLocked()
    }

    /** Marks engine initialization without assuming that its native view is already running. */
    internal fun noteEngineInitialized() {
        synchronized(drainLock) {
            engineInitialized = true
            handoffThread = Thread.currentThread()
            wakeThread = handoffThread
        }
    }

    /** Called by the native frame hook; the first frame becomes the dispatcher owner thread. */
    internal fun drainNativeFrame() {
        synchronized(drainLock) {
            engineInitialized = true
            if (!nativeLoopActive) {
                nativeLoopActive = true
                PlatformGameMainDispatcherPump.stop()
            }

            val currentThread = Thread.currentThread()
            val owner = nativeThread
            when {
                owner == null -> {
                    nativeThread = currentThread
                    handoffThread = null
                    wakeThread = currentThread
                }

                owner === currentThread -> Unit
                !owner.isAlive -> {
                    nativeThread = currentThread
                    handoffThread = null
                    wakeThread = currentThread
                }

                else -> check(false) {
                    "GameMainDispatcher must only be drained by the native engine thread"
                }
            }
            lastNativeFrameNanos = System.nanoTime()
            drainTasksLocked()
        }
    }

    /**
     * Runs a suspend block directly on the callback thread. The common synchronous case returns
     * from the initial coroutine invocation without allocating a Job, dispatcher, or queue for
     * this call. Only a genuinely suspended block enters the wait-and-pump loop.
     */
    internal fun <T> runBlockingWithGameDispatcher(block: suspend () -> T): T {
        val state = bridgeFrames.get()
        val depth = state.depth
        val frame = state.frameAt(depth)
        val call = frame.begin(Thread.currentThread())
        state.depth = depth + 1
        try {
            @Suppress("UNCHECKED_CAST")
            val completion = call as Continuation<T>
            val immediate = block.startCoroutineUninterceptedOrReturn(completion)
            if (immediate !== COROUTINE_SUSPENDED) {
                @Suppress("UNCHECKED_CAST")
                return immediate as T
            }
            return awaitBridgeResult(frame, call)
        } finally {
            frame.end(call)
            state.depth = depth
        }
    }

    /** Same bridge for a suspend receiver function, avoiding a capturing lambda at native hooks. */
    internal fun <P, T> runBlockingWithGameDispatcher(block: suspend P.() -> T, receiver: P): T {
        val state = bridgeFrames.get()
        val depth = state.depth
        val frame = state.frameAt(depth)
        val call = frame.begin(Thread.currentThread())
        state.depth = depth + 1
        try {
            @Suppress("UNCHECKED_CAST")
            val completion = call as Continuation<T>
            val immediate = block.startCoroutineUninterceptedOrReturn(receiver, completion)
            if (immediate !== COROUTINE_SUSPENDED) {
                @Suppress("UNCHECKED_CAST")
                return immediate as T
            }
            return awaitBridgeResult(frame, call)
        } finally {
            frame.end(call)
            state.depth = depth
        }
    }

    private fun <T> awaitBridgeResult(frame: NativeBridgeFrame, call: NativeBridgeCall): T {
        var interrupted = false
        while (!call.isCompleted) {
            var madeProgress = frame.drain()

            if (canDrainOnCurrentThread()) {
                madeProgress = synchronized(drainLock) {
                    drainTasksLocked(includeUpdateTasks = false)
                } || madeProgress
            }

            madeProgress = frame.drain() || madeProgress
            if (call.isCompleted) break

            if (Thread.interrupted()) interrupted = true
            if (!madeProgress) {
                frame.awaitWork(BRIDGE_POLL_NANOS)
                if (Thread.interrupted()) interrupted = true
            }
        }
        if (Thread.interrupted()) interrupted = true
        if (interrupted) {
            // The bridge has no per-call Job to cancel. Wait until the event has fully unwound so
            // borrowed native event payloads cannot be released while a suspended listener still
            // references them, then surface the interruption to the original caller.
            Thread.currentThread().interrupt()
            throw InterruptedException("Native event callback was interrupted while suspended")
        }
        return call.result!!.getOrThrow() as T
    }

    private class NativeBridgeFrame : CoroutineDispatcher() {
        private val lock = Any()
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private var activeCall: NativeBridgeCall? = null

        @Volatile
        private var ownerThread: Thread? = null

        fun begin(owner: Thread): NativeBridgeCall = synchronized(lock) {
            check(activeCall == null) { "A native bridge frame cannot run two calls at once" }
            queue.clear()
            ownerThread = owner
            NativeBridgeCall(this).also { activeCall = it }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val call = context[NativeBridgeCall] ?: error("Native bridge dispatch lost its call context")
            val accepted = synchronized(lock) {
                if (activeCall !== call) false else {
                    queue.add(block)
                    true
                }
            }
            if (accepted) {
                val thread = ownerThread
                if (thread != null) LockSupport.unpark(thread)
            }
        }

        fun drain(): Boolean {
            var madeProgress = false
            while (true) {
                val task = queue.poll() ?: break
                task.run()
                madeProgress = true
            }
            return madeProgress
        }

        fun awaitWork(timeoutNanos: Long) {
            LockSupport.parkNanos(this, timeoutNanos)
        }

        fun complete(call: NativeBridgeCall, result: Result<Any?>) {
            val accepted = synchronized(lock) {
                if (activeCall !== call) false else {
                    call.complete(result)
                    true
                }
            }
            if (accepted) {
                val thread = ownerThread
                if (thread != null) LockSupport.unpark(thread)
            }
        }

        fun end(call: NativeBridgeCall) {
            synchronized(lock) {
                if (activeCall === call) {
                    activeCall = null
                    queue.clear()
                }
            }
            ownerThread = null
        }

        override fun toString(): String = "NativeBridgeDispatcher"
    }

    private class NativeBridgeThreadState {
        val frames = mutableListOf<NativeBridgeFrame>()
        var depth = 0

        fun frameAt(index: Int): NativeBridgeFrame {
            if (index < frames.size) return frames[index]
            return NativeBridgeFrame().also(frames::add)
        }
    }

    private class NativeBridgeCall(
        private val frame: NativeBridgeFrame,
    ) : AbstractCoroutineContextElement(Key), Continuation<Any?> {
        companion object Key : CoroutineContext.Key<NativeBridgeCall>

        @Volatile
        var result: Result<Any?>? = null
            private set

        @Volatile
        var isCompleted: Boolean = false
            private set

        override val context: CoroutineContext = frame + this

        override fun resumeWith(result: Result<Any?>) {
            frame.complete(this, result)
        }

        fun complete(result: Result<Any?>) {
            this.result = result
            isCompleted = true
        }
    }

    private fun canDrainOnCurrentThread(): Boolean = synchronized(drainLock) {
        refreshNativeLoopStateLocked()
        val currentThread = Thread.currentThread()
        val owner = nativeThread
        if (owner != null) {
            owner === currentThread
        } else if (nativeLoopActive) {
            handoffThread === currentThread
        } else {
            PlatformGameMainDispatcherPump.isPumpThread() || !PlatformGameMainDispatcherPump.isRunning()
        }
    }

    /** Refreshes loop ownership while holding the same lock used by both drain paths. */
    private fun refreshNativeLoopStateLocked(): Boolean {
        val recentNativeFrame = nativeThread != null &&
                System.nanoTime() - lastNativeFrameNanos < NATIVE_FRAME_GRACE_PERIOD_NANOS
        val reportedLoopActive = engineInitialized &&
                PlatformGameMainDispatcherPump.isNativeLoopActive() == true
        nativeLoopActive = reportedLoopActive || recentNativeFrame
        if (!nativeLoopActive) {
            nativeThread = null
            handoffThread = null
            wakeThread = null
        }
        return nativeLoopActive
    }

    private fun drainTasksLocked(includeUpdateTasks: Boolean = true, stopAtHandoff: Boolean = false): Boolean {
        var madeProgress = false
        if (includeUpdateTasks) {
            for (task in updateTasks) {
                if (stopAtHandoff && nativeLoopActive) return madeProgress
                task.run()
                madeProgress = true
            }
        }
        while (true) {
            if (stopAtHandoff && nativeLoopActive) return madeProgress
            val task = taskQueue.poll() ?: break
            task.run()
            madeProgress = true
        }
        return madeProgress
    }

    fun addUpdateTask(task: Runnable) {
        updateTasks.add(task)
    }

    fun removeUpdateTask(task: Runnable) {
        updateTasks.remove(task)
    }

    private const val BRIDGE_POLL_NANOS = 1_000_000L
    private const val NATIVE_FRAME_GRACE_PERIOD_NANOS = 1_000_000_000L
}

internal expect object PlatformGameMainDispatcherPump {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun isPumpThread(): Boolean
    fun isNativeLoopActive(): Boolean?
}

private val gameTaskFailures = CoroutineExceptionHandler { _, failure ->
    val engine = com.corrodinggames.rts.gameFramework.GameEngine.getInstance()
    if (engine != null) io.github.rwpp.api.game.impl.NativeGameFaults.report(engine, failure, "game task")
    else {
        val error = io.github.rwpp.api.game.impl.nativeGameFailure(failure)
        io.github.rwpp.api.logging.logger().warn("Game task failed before engine initialization", error)
    }
}

val GameScope = CoroutineScope(GameMainDispatcher + SupervisorJob() + gameTaskFailures)
