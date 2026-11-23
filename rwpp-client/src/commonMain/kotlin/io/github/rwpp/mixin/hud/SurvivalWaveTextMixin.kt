/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import android.graphics.Paint
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.game.hud.SurvivalWavePresentation
import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

@AsmMixin("com/corrodinggames/rts/gameFramework/trigger/TriggerEngine")
object SurvivalWaveTextMixin {
    @Redirect(
        method = "drawMapTexts(F)V", at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/GraphicsEngine.drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V",
        ), require = 1,
    )
    fun drawMapText(graphics: GraphicsEngine, text: String, x: Float, y: Float, paint: Paint) {
        val engine = GameEngine.getInstance()
        val mission = engine?.triggerEngine
        if (mission != null && (paint === mission.waveHeaderTextPaint || paint === mission.waveUnitsTextPaint ||
                    paint === mission.waveCountdownTextPaint || paint === mission.wavePreviewTextPaint)
        ) {
            if (!SurvivalWavePresentation.replacesNativeText) graphics.drawText(text, x, y, paint)
            return
        }
        val scale = GameVisualSettings.mapTextScale(engine?.zoomScale ?: 1f)
        if (scale == 1f) {
            graphics.drawText(text, x, y, paint)
            return
        }
        // Keep the native font atlas at a stable size as the camera zooms.
        graphics.save()
        try {
            graphics.scaleAroundPoint(scale, scale, x, y)
            graphics.drawText(text, x, y, paint)
        } finally {
            graphics.restore()
        }
    }
}
