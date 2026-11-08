/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.graphics.Color
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.replay.AeroLivePerspective

/** Native Unit.selectionPaintAlly, ARGB(180, 255, 255, 0). */
internal val AeroAllyHighlight = Color(0xB4FFFF00)

/** Native InterfaceEngine.resourceTextPaintAlt, ARGB(255, 240, 240, 0). */
internal val AeroAllyText = Color(0xFFF0F000)

/** Native Unit.selectionPaintEnemy uses red; this keeps the previous Aero tone. */
internal val AeroEnemyHighlight = Color(0xFFFF6565)

/**
 * Native marks a unit that is not the viewer's own with selectionPaintAlly and a hostile unit
 * with selectionPaintEnemy. Aero reproduces that relation, including the live observer case,
 * where every watched unit belongs to another player.
 */
internal fun aeroSelectionHighlightColor(
    ownTeam: Boolean,
    enemy: Boolean,
    observing: Boolean,
    replay: Boolean,
    accent: Color,
): Color = when {
    enemy -> AeroEnemyHighlight
    observing -> AeroAllyHighlight
    ownTeam || replay -> accent
    else -> AeroAllyHighlight
}

/** True while the HUD presents a team chosen in observer mode instead of the local team. */
internal fun aeroObservingTeam(engine: GameEngine): Boolean = AeroLivePerspective.selected(engine) != null

/**
 * Native InterfaceEngine.drawResourceSidebar draws a second, yellow resource block for the first
 * controllable selected unit of another team. The replay and observer branch never reaches it.
 */
internal fun aeroSecondaryResourceTeam(
    engine: GameEngine,
    viewer: Team?,
    selected: OrderableUnit?,
    controllable: Boolean,
): Team? {
    if (viewer == null || selected == null || !controllable) return null
    if (engine.replayEngine.isPlayingReplay || aeroObservingTeam(engine)) return null
    val team = selected.team ?: return null
    if (team === viewer || team === Team.grayTeam) return null
    return team
}
