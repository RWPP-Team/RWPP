/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Paint
import com.corrodinggames.rts.gameFramework.render.CanvasLike

/** Copies native Paint state into a small per-thread nesting pool; original paints are never mutated. */
internal object WorldFilteredPaints {
    private class Pool {
        val paints = ArrayList<Paint>();
        var depth = 0
    }

    private val pools = ThreadLocal.withInitial { Pool() }
    fun <T> draw(original: Paint?, configure: (Paint) -> Unit = ::enableNativePaintFiltering, block: (Paint) -> T): T {
        val pool = pools.get()!!
        val slot = pool.depth++
        if (slot == pool.paints.size) pool.paints.add(Paint())
        val copy = pool.paints[slot]
        if (original == null) copy.reset() else copy.set(original)
        configure(copy)
        return try {
            block(copy)
        } finally {
            pool.depth--
        }
    }
}

internal expect fun enableNativePaintFiltering(paint: Paint)
internal fun invokeNativeWorld(engine: Any, canvas: Any?, frameDelta: Float) =
    (engine as NativeWorldAccess).rwppDrawWorld(canvas as CanvasLike?, frameDelta)
