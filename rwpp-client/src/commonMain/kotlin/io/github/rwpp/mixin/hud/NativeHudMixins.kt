/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import android.graphics.Paint
import com.corrodinggames.rts.gameFramework.gui.PromptResult
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.game.minimap.AeroMinimapSignals
import io.github.rwpp.graphics.AERO_DRAG_SELECTION_STROKE_WIDTH
import io.github.rwpp.graphics.AeroPlacementPromptPainter
import io.github.rwpp.graphics.aeroPlacementPromptIcon
import io.github.rwpp.logic.hud.NativeHudVisibility
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.isSendingTeamChat
import io.github.rwpp.ui.showSendMessageDialog
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/game/units/buildings/Factory")
object AeroFactoryRallyMixin {
    @AsmInject(method = "drawRallyPointLine()V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun rally(callback: CallbackInfo) {
        if (NativeHudVisibility.aeroRallyPoints) callback.cancel()
    }
}

@AsmMixin("com/corrodinggames/rts/game/units/custom/CustomUnit")
object AeroCustomRallyMixin {
    @AsmInject(method = "drawRallyPointLine()V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun rally(callback: CallbackInfo) {
        if (NativeHudVisibility.aeroRallyPoints) callback.cancel()
    }
}

/** Cancel only HUD panels. Pointer handling, placement previews and native commands still run. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object NativeBattleHudMixin {
    @ModifyConstant(method = "handleDragSelection(F)V", constant = "1.0", require = 1)
    fun dragSelectionStrokeWidth(@Suppress("UNUSED_PARAMETER") nativeWidth: Float): Float =
        AERO_DRAG_SELECTION_STROKE_WIDTH

    @AsmInject(method = "selectMenuOption(I)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun chat(callback: CallbackInfo, self: Any, option: Int) {
        if (option == 21) {
            // The API's RoomReturnedEvent keeps the connection and restores the RWPP lobby.
            // Do not additionally launch a native battleroom Activity over the UI extension.
            callback.cancel()
        } else if (option == 13 || option == 16) {
            isSendingTeamChat = option == 16
            showSendMessageDialog()
            callback.cancel()
        }
    }

    @AsmInject(method = "renderActionBar(F)I", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun actionBar(callback: CallbackInfo) {
        if (NativeHudVisibility.hidden) callback.setReturnValue(0)
    }

    @AsmInject(method = "drawSelectionInfoBar(FI)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun selection(callback: CallbackInfo) {
        if (NativeHudVisibility.hidden) callback.cancel()
    }

    @AsmInject(method = "handleTopButtons(FZ)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun buttons(callback: CallbackInfo) {
        if (NativeHudVisibility.hidden) callback.cancel()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object NativeResourceHudMixin {
    @AsmInject(
        method = "drawSpecialActionTooltip(Lcom/corrodinggames/rts/game/units/actions/SpecialAction;ZLcom/corrodinggames/rts/game/units/Unit;ZZFZ)Z",
        target = InjectionPoint.HEAD, cancellable = true, require = 1
    )
    fun tooltip(callback: CallbackInfo) {
        if (NativeHudVisibility.hidden) callback.setReturnValue(false)
    }

    @AsmInject(method = "drawResourceSidebar(F)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun resources(callback: CallbackInfo) {
        if (NativeHudVisibility.hidden) callback.cancel()
    }
}

/** Replaces only the native button artwork; InterfaceEngine still owns prompt layout and hit testing. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object AeroPlacementPromptMixin {
    @ModifyReturnValue(
        method = "handlePromptButton(Lcom/corrodinggames/rts/gameFramework/gui/PromptResult;Z)Z",
        require = 1
    )
    fun promptSound(handled: Boolean, result: PromptResult, skipDesktopCheck: Boolean): Boolean {
        if (handled && NativeHudVisibility.hidden) io.github.rwpp.sound.Sounds.playSound(
            when (result) {
                PromptResult.no -> io.github.rwpp.sound.Sounds.cancel
                PromptResult.more -> io.github.rwpp.sound.Sounds.select2
                else -> io.github.rwpp.sound.Sounds.select
            }
        )
        return handled
    }

    @WrapOperation(
        method = "handlePromptButton(Lcom/corrodinggames/rts/gameFramework/gui/PromptResult;Z)Z",
        at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/GraphicsEngine.PENDING_drawImageRotatedClipped(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;FF)V"
        ),
        require = 3,
    )
    fun renderAeroPromptButton(
        graphics: GraphicsEngine,
        image: BitmapOrTexture,
        x: Float,
        y: Float,
        paint: Paint,
        rotation: Float,
        scale: Float,
        original: Operation<Unit>,
        result: PromptResult,
        skipDesktopCheck: Boolean,
    ) {
        if (NativeHudVisibility.hidden) {
            AeroPlacementPromptPainter.draw(graphics, aeroPlacementPromptIcon(result), x, y, scale)
        } else {
            original.call(graphics, image, x, y, paint, rotation, scale)
        }
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/WarLogInterface")
object NativeEventHudMixin {
    @AsmInject(method = "paint(F)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun events(callback: CallbackInfo, self: Any) {
        if (NativeHudVisibility.hidden) {
            (self as com.corrodinggames.rts.gameFramework.gui.WarLogInterface).pruneOldItems()
            callback.cancel()
        }
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MessageInterface")
object NativeChatHudMixin {
    @AsmInject(method = "paint(FI)I", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun chat(callback: CallbackInfo, self: Any, delta: Float, y: Int) {
        if (NativeHudVisibility.hidden) callback.setReturnValue(y)
    }
}

/**
 * The native transient message paints across the middle of the play field. The Aero HUD owns that
 * spot, so the countdown is advanced here and the AeroScreenMessage overlay presents the text. If
 * the accessor is missing the native body runs unchanged, so the message is never lost.
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object NativeScreenMessageHudMixin {
    @Volatile
    private var warnedMissingAccessor = false

    @AsmInject(method = "drawScreenMessage(F)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun message(callback: CallbackInfo, self: Any, delta: Float) {
        if (!NativeHudVisibility.hidden) return
        val state = self as? io.github.rwpp.game.nativeaccess.NativeScreenMessageAccess
        if (state == null) {
            if (!warnedMissingAccessor) {
                warnedMissingAccessor = true
                io.github.rwpp.api.logging.logger().warn(
                    "Screen message accessor missing; native draw left enabled"
                )
            }
            return
        }
        // The native body owns this countdown; keep it running while the draw is suppressed.
        state.rwppScreenMessageTimer(
            com.corrodinggames.rts.gameFramework.CommonUtils.applyDeadzone(state.rwppScreenMessageTimer(), delta)
        )
        callback.cancel()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MinimapHandler")
object NativeMinimapHudMixin {
    @AsmInject(method = "render(F)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun drawing(callback: CallbackInfo, self: Any, delta: Float) {
        if (NativeHudVisibility.hidden) {
            AeroMinimapSignals.capture(self as com.corrodinggames.rts.gameFramework.gui.MinimapHandler, delta)
            callback.cancel()
        } else {
            // The original renderer owns these animations again; discard stale Aero snapshots.
            AeroMinimapSignals.clear()
        }
    }

    @AsmInject(
        method = "minimapToWorld(FF)Landroid/graphics/Point;",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    fun input(callback: CallbackInfo) {
        if (NativeHudVisibility.hidden) callback.setReturnValue(null)
    }
}

@AsmMixin("com/corrodinggames/rts/game/units/Unit")
object AeroNativeSelectionMixin {
    @AsmInject(method = "drawSelection(F)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun selection(callback: CallbackInfo, self: Any) {
        if (NativeHudVisibility.aeroSelection) callback.cancel()
    }

    @AsmInject(method = "drawWaypointLines()V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun waypoints(callback: CallbackInfo, self: Any) {
        if (NativeHudVisibility.aeroOrders) callback.cancel()
    }

    @AsmInject(method = "drawOverlay(FZ)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun bars(callback: CallbackInfo, self: Any) {
        if (NativeHudVisibility.aeroBars) callback.cancel()
    }
}

/** Native server results and menu messages survive navigation on both platforms. */
@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object NativeCommandMessageBoxMixin {
    @AsmInject(
        method = "showMessageBox(Ljava/lang/String;Ljava/lang/String;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1
    )
    fun message(callback: CallbackInfo, self: Any, title: String?, message: String?) {
        // loadCallback is the native dialog presenter too; Aero owns presentation from here.
        UI.showNativeMessage(title, message)
        callback.cancel()
    }

    @AsmInject(
        method = "showAlert(Ljava/lang/String;I)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    fun duplicateAlert(callback: CallbackInfo, self: Any, message: String?, duration: Int) {
        // Native kick handling emits both a message box and an alert for the identical result.
        if (message != null && UI.warning?.nativeMessage == message) callback.cancel()
    }
}
