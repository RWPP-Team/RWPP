/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.units.comp

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.game.team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.game.units.MovementType
import io.github.rwpp.api.graphics.ShaderProgram
import io.github.rwpp.ui.color.getTeamColor
import io.github.rwpp.utils.argb
import org.koin.core.annotation.Single
import kotlin.math.ceil
import io.github.rwpp.api.logging.logger

@Single
class EntityRangeUnitComp {
    var showAttackRange: Boolean = false

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

        private val game: Game by lazy { appKoin.get<Game>() }
        private val settings by lazy { appKoin.get<Settings>() }
        private val app by lazy { appKoin.get<AppContext>() }

        val layerGroups: MutableMap<Int, ArrayList<Unit>> = mutableMapOf()

        var entityRangeShader: ShaderProgram? = null

        val teamPaintMap = mutableMapOf<Int, Paint>()

        // 目前仅PC可跑
        val vertexSource = """
            #version 120

            uniform float uPriority;

            void main() {
                vec4 pos = gl_ModelViewProjectionMatrix * gl_Vertex;
                
                gl_Position = vec4(pos.xy, uPriority * pos.w, pos.w);
                
                gl_TexCoord[0] = gl_MultiTexCoord0;
                gl_FrontColor = gl_Color;
            }
        """.trimIndent()

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
            val orderableUnit = unit as? OrderableUnit ?: return
            if (!unit.dead && orderableUnit.getMaxAttackRange() > 70) {
                val condition = when (unit.getUnitTypeInterface().movementType) {
                    MovementType.BUILDING, MovementType.NONE -> {
                        settings.showBuildingAttackRange
                    }

                    MovementType.LAND, MovementType.OVER_CLIFF, MovementType.OVER_CLIFF_WATER, MovementType.HOVER -> {
                        settings.showAttackRangeUnit == "Land" || settings.showAttackRangeUnit == "All"
                    }

                    MovementType.AIR -> {
                        settings.showAttackRangeUnit == "Air" || settings.showAttackRangeUnit == "All"
                    }

                    MovementType.WATER -> {
                        settings.showAttackRangeUnit == "All"
                    }
                }
                val comp = appKoin.get<EntityRangeUnitComp>()
                if (condition || comp.showAttackRange) {
                    val priority =
                        if (game.gameRoom.localPlayer.team != unit.team.allyTeam) 0.1f else 0.8f
                    entityRangeShader?.setUniform1f("uPriority", priority)
                    drawCircle(
                        unit.x - GameEngine.getInstance().cameraX.toFloat(),
                        unit.y - GameEngine.getInstance().cameraY.toFloat(),
                        range,
                        paint
                    )
                }
            }
        }

        fun beforeDrawRange() {
            val unitsSnapShot = com.corrodinggames.rts.gameFramework.GameObject.fastGameObjectList
            .filterIsInstance<com.corrodinggames.rts.gameFramework.GameObject>()
            .filter { it.renderedLastFrame }
            layerGroups.values.forEach { it.clear() }
            unitsSnapShot.forEach { unit ->
                if (unit is Unit) {
                    layerGroups.getOrPut(unit.team.allyTeam, { arrayListOf() }).add(unit)
                }
            }
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
            logger().info("try loading shaders")
            if (app.isDesktop()) {
                entityRangeShader = ShaderProgram.loadShaderFragFromString(null, vertexSource)
            }
            GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways {
                layerGroups.clear()
            }
        }
    }
}
