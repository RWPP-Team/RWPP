/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Paint
import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwpp.logic.GameVisualSettings
import kotlin.math.roundToInt
import com.corrodinggames.rts.game.units.Unit as NativeUnit

/** The scope covers only the object's native world draw, not the HUD or map. */
internal object NativeObjectOpacity {
    private class Context {
        var alpha = 1f
    }

    private val context = ThreadLocal.withInitial { Context() }
    fun current(): Float = context.get()!!.alpha

    private fun alpha(obj: GameObject, settings: io.github.rwpp.config.Settings?): Float {
        if (settings == null || settings.unitOpacity >= 1f && settings.projectileOpacity >= 1f) return 1f
        return when (obj) {
            is NativeUnit -> settings.unitOpacity
            is Projectile -> settings.projectileOpacity
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    fun drawObject(obj: GameObject, delta: Float): Boolean {
        val settings = GameVisualSettings.get()
        if (obj is NativeUnit && settings != null &&
            (settings.unitAppearancePaintEnabled || settings.unitAppearanceOutlineBrighteningEnabled)
        ) {
            return UnitAppearanceRenderBridge.withUnit(obj) {
                drawObjectWithOpacity(obj, delta, settings)
            }
        }
        return drawObjectWithOpacity(obj, delta, settings)
    }

    private fun drawObjectWithOpacity(
        obj: GameObject,
        delta: Float,
        settings: io.github.rwpp.config.Settings?,
    ): Boolean {
        val objectAlpha = alpha(obj, settings)
        if (objectAlpha >= 1f) return obj.draw(delta)
        val state = context.get()!!
        val old = state.alpha
        state.alpha = objectAlpha
        return try {
            obj.draw(delta)
        } finally {
            state.alpha = old
        }
    }

    fun drawBack(obj: GameObject, delta: Float) {
        val alpha = alpha(obj, GameVisualSettings.get())
        if (alpha >= 1f) {
            obj.PENDING_d_float(delta); return
        }
        val state = context.get()!!
        val old = state.alpha
        state.alpha = alpha
        try {
            obj.PENDING_d_float(delta)
        } finally {
            state.alpha = old
        }
    }

    fun drawWorldIcon(obj: GameObject, delta: Float): Boolean {
        val alpha = alpha(obj, GameVisualSettings.get())
        if (alpha >= 1f) return obj.drawMinimap(delta)
        val state = context.get()!!
        val old = state.alpha
        state.alpha = alpha
        return try {
            obj.drawMinimap(delta)
        } finally {
            state.alpha = old
        }
    }

    fun adjust(paint: Paint) {
        paint.alpha = (paint.alpha * current()).roundToInt().coerceIn(0, 255)
    }
}
