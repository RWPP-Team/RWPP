/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.desktop

import org.lwjgl.opengl.Display

/**
 * Windows attaches an IME context to the native LWJGL window. While that context is attached,
 * the system consumes IME hotkeys such as a bare Shift for Chinese/English switching before
 * Slick can read the key, so gameplay modifiers are lost.
 *
 * Gameplay detaches the context from that window only. Text fields use the separate hidden AWT
 * IME window, so their input method is never touched here. The gate re-attaches the exact
 * context it removed as soon as a text field takes focus or the game screen is left.
 */
internal object DesktopSystemShortcutGate {
    private val isWindows = System.getProperty("os.name", "").contains("windows", ignoreCase = true)

    @Volatile
    private var supported = isWindows

    private var detachedWindow = 0L
    private var savedContext = 0L
    private var lastAppliedNanos = 0L
    private var resolvedWindow = 0L
    private var resolvedAtNanos = 0L

    /** Called on the thread that owns the LWJGL window, once per game frame. */
    @Synchronized
    fun update(disableSystemShortcuts: Boolean) {
        if (!supported) return
        if (!disableSystemShortcuts) {
            restoreLocked()
            return
        }
        val now = System.nanoTime()
        if (detachedWindow == 0L) {
            val window = gameWindowHandle()
            if (window == 0L) return
            val previous = Imm32.associate(window, 0L)
            if (previous == null) {
                // No native access (older JDK, non-Windows, or a restricted runtime).
                supported = false
                return
            }
            detachedWindow = window
            savedContext = previous
            lastAppliedNanos = now
            return
        }
        if (now - lastAppliedNanos <= 1_000_000_000L) return
        // Windows can attach a fresh default context after a focus or resolution change.
        val window = gameWindowHandle()
        if (window == 0L) return
        if (window != detachedWindow) {
            restoreLocked()
            detachedWindow = 0L
            return
        }
        Imm32.associate(window, 0L)
        lastAppliedNanos = now
    }

    @Synchronized
    fun restore() = restoreLocked()

    private fun restoreLocked() {
        val window = detachedWindow
        if (window == 0L) return
        detachedWindow = 0L
        val context = savedContext
        savedContext = 0L
        Imm32.associate(window, context)
    }

    /** LWJGL 2 keeps the native window handle on its platform DisplayImplementation. */
    private fun gameWindowHandle(): Long {
        val now = System.nanoTime()
        if (resolvedWindow != 0L && now - resolvedAtNanos < 1_000_000_000L) return resolvedWindow
        resolvedWindow = runCatching {
            val implementationField = Display::class.java.getDeclaredField("display_impl")
                .apply { isAccessible = true }
            val implementation = implementationField.get(null) ?: return@runCatching 0L
            val windowField = implementation.javaClass.getDeclaredField("hwnd")
                .apply { isAccessible = true }
            windowField.getLong(implementation)
        }.getOrDefault(0L)
        resolvedAtNanos = now
        return resolvedWindow
    }
}

/**
 * HIMC ImmAssociateContext(HWND, HIMC) through the JDK foreign-function API. Handles and
 * pointers are passed as longs, which matches the Windows x64 C ABI without allocating
 * memory segments. Every failure is contained so a restricted runtime simply loses the gate.
 */
private object Imm32 {
    private val downcall: java.lang.invoke.MethodHandle? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val linker = java.lang.foreign.Linker.nativeLinker()
            val arena = java.lang.foreign.Arena.ofShared()
            val lookup = java.lang.foreign.SymbolLookup.libraryLookup("imm32.dll", arena)
            val symbol = lookup.find("ImmAssociateContext")
                .orElseThrow { NoSuchElementException("ImmAssociateContext") }
            val longLayout = java.lang.foreign.ValueLayout.JAVA_LONG
            linker.downcallHandle(
                symbol,
                java.lang.foreign.FunctionDescriptor.of(longLayout, longLayout, longLayout)
            )
        }.getOrNull()
    }

    /** Returns the previously attached context, or null when the native call is unavailable. */
    fun associate(window: Long, context: Long): Long? {
        val handle = downcall ?: return null
        return runCatching { handle.invokeWithArguments(window, context) as Long }.getOrNull()
    }
}
