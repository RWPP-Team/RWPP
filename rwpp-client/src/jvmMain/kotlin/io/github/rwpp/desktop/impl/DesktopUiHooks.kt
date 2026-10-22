/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.game.units.actions.CutSpecialAction
import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.BattleInterface
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngineButtonIds
import com.corrodinggames.rts.gameFramework.gui.UnitHotSelectionGroup
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.api.game.Game
import com.corrodinggames.rts.game.Team
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import io.github.rwpp.api.i18n.readI18n
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyReturnValue
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
import java.util.ArrayList
import java.util.Locale

/** Adds the optional clock and ping beside the game's native top buttons. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object DesktopTopButtonsMixin {
    @AsmInject(
        method = "handleTopButtons(FZ)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun drawExtensionTopBar(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") self: Any,
        @Suppress("UNUSED_PARAMETER") deltaTime: Float,
        @Suppress("UNUSED_PARAMETER") drawNativeButtons: Boolean,
    ) {
        if (appKoin.get<Settings>().displayTimeInGame && appKoin.get<Game>().state.isPlaying()) {
            val engine = GameEngine.getInstance()
            val elapsedSeconds = (engine.gameTimeMillis / 1_000).coerceAtLeast(0)
            val text = String.format(Locale.ROOT, "%d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
            val paint = engine.interfaceEngine.screenMessageTextPaint
            engine.graphics.drawText(text, engine.screenCenterX, paint.textSize + 7f, paint)
        }
    }
}

/** Restores the optional attack-range actions from the legacy desktop battle UI. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object DesktopAttackRangeActionsMixin {
    @ModifyReturnValue(
        method = "getSpecialActionsForSelection(Lcom/corrodinggames/rts/game/units/Unit;Ljava/util/ArrayList;)Ljava/util/ArrayList;",
        require = 1,
    )
    fun appendAttackRangeActions(
        result: ArrayList<*>?,
        selectedUnit: GameUnit?,
        @Suppress("UNUSED_PARAMETER") selectedUnits: ArrayList<*>?,
    ): ArrayList<*>? {
        if (result == null || !appKoin.get<Settings>().showExtraButton) return result
        @Suppress("UNCHECKED_CAST")
        val actions = result as ArrayList<Any>
        if (selectedUnit == null) {
            actions.addIfMissing(ShowBuildingAttackRangeAction)
            actions.addIfMissing(ShowUnitAttackRangeAction)
        } else {
            val player = selectedUnit.team as? Team
            if (player != null && player.allyTeam != appKoin.get<Game>().gameRoom.localPlayer.allyTeam) {
                actions.addIfMissing(ShowSelectedUnitAttackRangeAction)
            }
        }
        return actions
    }

    private fun ArrayList<Any>.addIfMissing(action: CutSpecialAction) {
        if (none { it is CutSpecialAction && it.actionIdString == action.actionIdString }) add(action)
    }
}

private object ShowBuildingAttackRangeAction : CutSpecialAction("c_show_attack_range_building") {
    override fun getDisplayName(): String = "Show Attack Range Building"

    override fun getDisplayDescription(): String = readI18n("rwpp.settings.showAttackRange")

    override fun onButtonClicked(unit: GameUnit, alternate: Boolean): Boolean {
        val settings = appKoin.get<Settings>()
        settings.showBuildingAttackRange = !settings.showBuildingAttackRange
        return true
    }

    override fun compareTo(other: Any?): Int = 0
}

private object ShowUnitAttackRangeAction : CutSpecialAction("c_show_attack_range_units") {
    override fun getDisplayName(): String = "Show Attack Range Units"

    override fun getDisplayDescription(): String =
        "${readI18n("rwpp.settings.showAttackRange")}\n${appKoin.get<Settings>().showAttackRangeUnit}"

    override fun onButtonClicked(unit: GameUnit, alternate: Boolean): Boolean {
        val settings = appKoin.get<Settings>()
        val types = Settings.unitAttackRangeTypes
        val index = types.indexOf(settings.showAttackRangeUnit)
        settings.showAttackRangeUnit = types.getOrNull(index + 1) ?: types.first()
        return true
    }

    override fun compareTo(other: Any?): Int = 0
}

private object ShowSelectedUnitAttackRangeAction : CutSpecialAction("c_show_attack_range") {
    override fun getDisplayName(): String = "Show Attack Range"

    override fun getDisplayDescription(): String = readI18n("rwpp.settings.showAttackRange")

    override fun onButtonClicked(unit: GameUnit, alternate: Boolean): Boolean {
        val gameUnit = unit as? GameUnit ?: return false
        val component = appKoin.get<EntityRangeUnitComp>()
        component.showAttackRange = !component.showAttackRange
        return true
    }

    override fun compareTo(other: Any?): Int = 0
}

/** Keeps native hotkeys/group maintenance and draws the configurable RWPP group row. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object DesktopUnitGroupUiMixin {
    private val groupsField by lazy {
        BattleInterface::class.java.getDeclaredField("hotSelectionGroups").apply { isAccessible = true }
    }
    private val pointerDownField by lazy {
        InterfaceEngine::class.java.getDeclaredField("pointerDown").apply { isAccessible = true }
    }
    private val overlayFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val overlayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    @WrapMethod(method = "handleUnitGroups(F)V", require = 1)
    fun drawConfiguredUnitGroups(deltaTime: Float, operation: Operation<*>) {
        val engine = GameEngine.getInstance()
        val nativeShowGroups = engine.settings.showUnitGroups
        if (nativeShowGroups) engine.settings.showUnitGroups = false
        try {
            operation.call(deltaTime)
        } finally {
            engine.settings.showUnitGroups = nativeShowGroups
        }

        if (nativeShowGroups) drawGroups(engine, deltaTime)
    }

    @Suppress("UNCHECKED_CAST")
    private fun drawGroups(engine: GameEngine, deltaTime: Float) {
        val groups = groupsField.get(engine.interfaceEngine.battleInterface) as? ArrayList<UnitHotSelectionGroup> ?: return
        val settings = appKoin.get<Settings>()
        val count = settings.maxDisplayUnitGroupCount.coerceIn(0, groups.size)
        if (count == 0) return

        val slotWidth = ((engine.sidebarWidth - 20f) / 3f).toInt()
        val buttonWidth = (slotWidth - 5).coerceAtLeast(1)
        val buttonHeight = (31f * engine.dipScale).toInt().coerceAtLeast(1)
        val bottom = (engine.gameAreaHeight - 30f * engine.dipScale).toInt()
        var left = (
            engine.screenWidth - engine.sidebarWidth - slotWidth * (count - 3) -
                settings.displayUnitGroupXOffset + 10f
            ).toInt()

        for (index in 0 until count) {
            val group = groups[index]
            group.selectFlash = CommonUtils.applyDeadzone(group.selectFlash, 0.01f * deltaTime)
            group.addGroupFlash = CommonUtils.applyDeadzone(group.addGroupFlash, 0.01f * deltaTime)
            group.replaceFlash = CommonUtils.applyDeadzone(group.replaceFlash, 0.01f * deltaTime)
            val color = Color.argb(
                50,
                (100f + group.replaceFlash * 100f).toInt().coerceIn(0, 255),
                (100f + group.addGroupFlash * 100f).toInt().coerceIn(0, 255),
                (100f + group.selectFlash * 100f).toInt().coerceIn(0, 255),
            )
            val label = when {
                group.units.isNotEmpty() -> group.units.size.toString()
                engine.interfaceEngine.debugTextMode -> "Empty"
                else -> "(${index + 1})"
            }
            val pressed = engine.interfaceEngine.drawButtonFilled(
                left,
                bottom,
                buttonWidth,
                buttonHeight,
                label,
                InterfaceEngineButtonIds.none,
                true,
                color,
            ) && engine.interfaceEngine.activeSpecialAction == null &&
                engine.interfaceEngine.isPointerNotDragging()

            if (pressed) {
                group.holdTimer += deltaTime
                engine.interfaceEngine.clearTapState()
                drawHoldProgress(engine, left, bottom, buttonWidth, buttonHeight, group.holdTimer)
            } else if (group.holdTimer != 0f && !pointerDownField.getBoolean(engine.interfaceEngine)) {
                when {
                    group.holdTimer > 100f -> {
                        group.clearUnits()
                        group.addSelectedUnits()
                        group.replaceFlash = 1f
                    }
                    group.holdTimer > 50f -> {
                        group.addSelectedUnits()
                        engine.interfaceEngine.cancelActiveAction()
                        engine.interfaceEngine.clearUnitSelection()
                        group.selectUnits()
                        group.addGroupFlash = 1f
                    }
                    group.units.isNotEmpty() -> {
                        engine.interfaceEngine.cancelActiveAction()
                        engine.interfaceEngine.clearUnitSelection()
                        group.selectUnits()
                        group.selectFlash = 1f
                    }
                    else -> {
                        group.clearUnits()
                        group.addSelectedUnits()
                        group.addGroupFlash = 1f
                    }
                }
                group.holdTimer = 0f
            }
            left += slotWidth
        }
    }

    private fun drawHoldProgress(
        engine: GameEngine,
        left: Int,
        bottom: Int,
        width: Int,
        height: Int,
        holdTime: Float,
    ) {
        val phase = when {
            holdTime < 50f -> HoldPhase("Select Group", "(Hold for more..)", holdTime / 50f, Color.rgb(0, 200, 0))
            holdTime < 100f -> HoldPhase("Add to Group", "(Hold for more..)", (holdTime - 50f) / 50f, Color.rgb(200, 0, 0))
            else -> HoldPhase("Replace Group", "", 1f, Color.rgb(200, 0, 0))
        }
        val scale = engine.dipScale
        val boxWidth = (width * 2.5f).toInt().coerceAtLeast(width)
        val boxHeight = (40f * scale).toInt().coerceAtLeast(1)
        val centerX = left + width / 2
        val top = bottom - (35f * scale).toInt() - boxHeight
        overlayFill.color = Color.argb(190, 45, 45, 45)
        engine.graphics.drawRectAlt(Rect(centerX - boxWidth / 2, top, centerX + boxWidth / 2, top + boxHeight), overlayFill)
        overlayText.textSize = engine.interfaceEngine.smallTextPaint.textSize
        engine.graphics.drawText(phase.title, centerX.toFloat(), top + overlayText.textSize, overlayText)
        if (phase.subtitle.isNotEmpty()) {
            engine.graphics.drawText(phase.subtitle, centerX.toFloat(), top + overlayText.textSize * 2f, overlayText)
        }
        overlayFill.color = phase.color
        val fillWidth = (boxWidth * phase.progress.coerceIn(0f, 1f)).toInt()
        engine.graphics.drawRectAlt(
            Rect(centerX - boxWidth / 2, bottom - height, centerX - boxWidth / 2 + fillWidth, bottom),
            overlayFill,
        )
    }

    private data class HoldPhase(val title: String, val subtitle: String, val progress: Float, val color: Int)
}

@AsmMixin("com/corrodinggames/rts/game/units/actions/GuardAction")
object DesktopGuardActionSizeMixin {
    @ModifyReturnValue(method = "getButtonWidthScale()F", require = 1)
    fun enlargeGuardAction(result: Float): Float =
        if (appKoin.get<Settings>().enableLargerKeys) maxOf(result, 1f) else result
}

@AsmMixin("com/corrodinggames/rts/game/units/actions/PatrolAction")
object DesktopPatrolActionSizeMixin {
    @ModifyReturnValue(method = "getButtonWidthScale()F", require = 1)
    fun enlargePatrolAction(result: Float): Float =
        if (appKoin.get<Settings>().enableLargerKeys) maxOf(result, 1f) else result
}
