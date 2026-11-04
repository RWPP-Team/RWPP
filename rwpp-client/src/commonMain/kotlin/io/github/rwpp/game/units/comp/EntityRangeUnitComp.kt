/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.units.comp

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.hideFullHealthBarDuringMultiSelection
import io.github.rwpp.game.team
import io.github.rwpp.ui.color.getTeamColor
import io.github.rwpp.utils.argb
import org.koin.core.annotation.Single
import kotlin.math.ceil

@Single
class EntityRangeUnitComp {
    private val explicitRanges = java.util.WeakHashMap<Unit, Boolean>()
    fun toggleAttackRange(unit: Unit): Boolean = synchronized(explicitRanges) {
        if (explicitRanges.remove(unit) == null) {
            explicitRanges[unit] = true; true
        } else false
    }

    fun isAttackRangeShown(unit: Unit): Boolean = synchronized(explicitRanges) { explicitRanges[unit] == true }
    fun hasExplicitRanges(): Boolean = synchronized(explicitRanges) {
        explicitRanges.keys.removeAll { it.dead || it.removed }
        explicitRanges.isNotEmpty()
    }

    fun clearAttackRanges() = synchronized(explicitRanges) { explicitRanges.clear() }

    init {
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways { clearAttackRanges() }
    }

    // showUnitWayPoint
    fun onDraw(unit: Unit, graphics: GraphicsEngine, delta: Float) {
        val orderableUnit = unit as? OrderableUnit ?: return
        val target = orderableUnit.attackTarget
        if (!unit.dead && orderableUnit.getMaxAttackRange() > 70) {
            if (settings.showUnitTargetLine
                && (!game.state.multiplayer || game.gameRoom.localPlayer.team == unit.team.allyTeam)
                && target != null
                && !target.dead
            ) {
                graphics.drawLine(
                    unit.x - GameEngine.getInstance().cameraX.toFloat(),
                    unit.y - GameEngine.getInstance().cameraY.toFloat(),
                    target.x - GameEngine.getInstance().cameraX.toFloat(),
                    target.y - GameEngine.getInstance().cameraY.toFloat(),
                    paintBlack
                )
            }
        }
    }

    fun onDrawBar(unit: Unit, graphics: GraphicsEngine, rect: RectF, paint: Paint) {
        var realPaint = paint

        //以颜色判断是否在绘制血条
        val isHealthBar = Companion.isNativeHealthBarPaintColor(paint.color)
        if (isHealthBar && hideFullHealthBarDuringMultiSelection(
                selected = unit.selected,
                selectedUnitCount = GameEngine.getInstance().interfaceEngine.getSelectedUnitCount(),
                hp = unit.hp,
                maxHp = unit.maxHp,
            )
        ) return

        if ((paint.color == argb1 || paint.color == argb2) && settings.improvedHealthBar) {
            val percentage = ceil(unit.hp) / unit.maxHp
            realPaint = when (percentage) {
                in 0.6..1.0 -> paint
                in 0.3..0.6 -> paintYellow
                else -> paintBlack
            }
        }

        graphics.drawRect(rect, realPaint)
    }

    companion object {
        val paintBlack by lazy { Paint().apply { color = argb(255, 0, 0, 0) } }
        val paintYellow by lazy { Paint().apply { color = argb(200, 237, 145, 33) } }

        private val argb1 = argb(200, 183, 44, 44)
        private val argb2 = argb(200, 0, 150, 0)
        private val healthBarEnemyOutline = argb(120, 255, 60, 60)
        private val healthBarAllyOutline = argb(120, 0, 230, 0)
        private val healthBarChange = argb(100, 232, 208, 26)

        internal fun isNativeHealthBarPaintColor(color: Int): Boolean =
            isNativeHealthFillPaintColor(color) || color == healthBarEnemyOutline ||
                    color == healthBarAllyOutline || color == healthBarChange

        private fun isNativeHealthFillPaintColor(color: Int): Boolean = color == argb1 || color == argb2

        private val game: Game by lazy { appKoin.get<Game>() }
        private val settings by lazy { appKoin.get<Settings>() }

        val layerGroups: MutableMap<Int, ArrayList<Unit>> = mutableMapOf()

        val teamPaintMap = mutableMapOf<Int, Paint>()

        fun GraphicsEngine.drawRange(unit: Unit, paint: Paint, range: Float) {
            drawRangeWith(unit, paint, range) { x, y, radius, nativePaint ->
                drawCircle(x, y, radius, nativePaint)
            }
        }

        fun drawRangeWith(
            unit: Unit,
            paint: Paint,
            range: Float,
            drawCircle: (x: Float, y: Float, radius: Float, paint: Paint) -> kotlin.Unit,
        ) {
            // Range visuals are now collected from visible world entities and composited as one
            // team/kind mask by AeroWorldEffects. Leaving this legacy hook empty prevents it from
            // drawing a second, unmerged circle when the detailed style is disabled.
        }

        fun beforeDrawRange() {
            // Ranges are owned by the shared Compose world layer now. Clear the legacy native
            // batches rather than snapshotting every game object for a renderer that is disabled.
            layerGroups.clear()
        }

        @Suppress("MemberVisibilityCanBePrivate")
        fun getTeamPaint(team: Int): Paint {
            val teamPaint = teamPaintMap.getOrPut(
                team
            ) {
                Paint().apply {
                    color = getTeamColor(team % 10).copy(alpha = .2f).toArgb()
                    style = Paint.Style.FILL
                }
            }
            return teamPaint
        }

        init {
            GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways {
                layerGroups.clear()
            }
        }
    }
}
