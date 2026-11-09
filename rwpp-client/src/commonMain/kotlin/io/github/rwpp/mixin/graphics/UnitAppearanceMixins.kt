/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.TeamColorMode
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.game.hud.isAeroLiveObserver
import io.github.rwpp.graphics.UnitAppearanceRenderBridge
import io.github.rwpp.logic.GameVisualSettings
import io.github.rwpp.logic.hud.NativeHudVisibility
import kim.der.asm.api.annotation.*

/** Retains the native team-mask source for each cached texture variant without changing Team. */
@AsmMixin("com/corrodinggames/rts/game/Team")
object UnitAppearanceTeamColorSourceMixin {
    @WrapMethod(
        method = "getTeamColorVariants(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Lcom/corrodinggames/rts/game/TeamColorMode;Z)[Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;",
        require = 1,
    )
    fun retain(
        source: BitmapOrTexture, mode: TeamColorMode, lazy: Boolean,
        original: Operation<Array<BitmapOrTexture>>
    ): Array<BitmapOrTexture> =
        original.call(source, mode, lazy).also { UnitAppearanceRenderBridge.registerTeamColorSources(source, mode, it) }
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object UnitAppearanceSelectionDrawScopeMixin {
    @WrapOperation(
        method = "drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/GameObject.drawSelection(F)V",
        ),
        require = 1,
    )
    fun selectionDrawScope(
        gameObject: GameObject,
        deltaTime: Float,
        original: Operation<kotlin.Unit>,
    ) {
        val unit = gameObject as? Unit ?: return original.call(gameObject, deltaTime)
        val engine = GameEngine.getInstance()
        // A live observer watches another player's units. Native compares against playerTeam,
        // which the observer draw already replaced, so it would pick the friendly colour. The
        // neutral viewer team makes Unit.drawSelection choose selectionPaintAlly instead.
        val neutralize = !NativeHudVisibility.aeroSelection && isAeroLiveObserver(engine)
        val previous = engine.playerTeam
        if (neutralize) engine.playerTeam = Team.grayTeam
        try {
            if (GameVisualSettings.get()?.unitAppearanceSelectionRingsEnabled != true) {
                return original.call(gameObject, deltaTime)
            }
            UnitAppearanceRenderBridge.withUnit(unit, selection = true) {
                original.call(gameObject, deltaTime)
            }
        } finally {
            if (neutralize) engine.playerTeam = previous
        }
    }
}

/** CanvasDirectGL overrides the bitmap entry points, so it needs the same local texture substitution. */
@AsmMixin("com/corrodinggames/rts/gameFramework/render/CanvasDirectGL")
object UnitAppearanceGlCanvasMixin {
    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;)V",
        require = 1,
    )
    fun image(
        texture: BitmapOrTexture, x: Float, y: Float, paint: Paint?,
        original: Operation<kotlin.Unit>
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture)
        original.call(appearance?.texture ?: texture, x, y, paint)
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;)V",
        require = 1,
    )
    fun rect(
        texture: BitmapOrTexture, source: Rect, destination: Rect, paint: Paint?,
        original: Operation<kotlin.Unit>
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        original.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, paint)
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        require = 1,
    )
    fun rectF(
        texture: BitmapOrTexture, source: Rect, destination: RectF, paint: Paint?,
        original: Operation<kotlin.Unit>
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        original.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, paint)
    }
}
