/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.custom.resources.CustomResource
import com.corrodinggames.rts.game.units.custom.resources.StoredResource
import com.corrodinggames.rts.game.units.custom.resources.StoredResources
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.replay.aeroPresentationTeam

internal data class AeroResourceHudLine(
    val text: String,
    val displayColor: Int?,
    val secondaryTeam: Boolean,
)

/** Samples the native sidebar state at 5 Hz and publishes only stable, changed snapshots. */
internal class NativeAeroResourceHudSampler(private val intervalMillis: Long = 200L) {
    private val rows = ArrayList<AeroResourceHudLine>(16)
    private val stored = StoredResources()
    private var lastSampleAt = Long.MIN_VALUE
    private var publishedRows: List<AeroResourceHudLine> = emptyList()

    /** Returns null until the next sample is due or when the displayed rows did not change. */
    fun sample(engine: GameEngine, now: Long): List<AeroResourceHudLine>? {
        if (!resourceHudSampleDue(lastSampleAt, now, intervalMillis)) return null
        lastSampleAt = now
        rows.clear()

        val ui = engine.interfaceEngine
        val player = aeroPresentationTeam(engine)
        var firstTeam: Team? = null
        if (engine.replayEngine.isPlayingReplay() || player?.isSpectator() == true) {
            val selected: OrderableUnit? = ui.getFirstSelectedOrderableUnit()
            val team = selected?.team
            if (team != null) {
                firstTeam = team
                appendTeamRows(team, secondaryTeam = false)
            }
        } else if (player != null) {
            if (player != Team.grayTeam && !player.isSpectator()) {
                firstTeam = player
                appendTeamRows(player, secondaryTeam = false)
            }
            val selected = ui.getFirstControllableSelectedUnit()
            if (selected != null && selected.team != firstTeam && ui.canPlayerControlUnit(selected)) {
                appendTeamRows(selected.team, secondaryTeam = true)
            }
        }

        if (!resourceHudRowsChanged(publishedRows, rows)) return null
        publishedRows = rows.toList()
        return publishedRows
    }

    /** Reuses a scratch StoredResources container; its contents are copied before native sorting. */
    private fun appendTeamRows(team: Team, secondaryTeam: Boolean) {
        stored.copyFrom(team.getStoredResourcesAlias())
        for (candidate in CustomResource.getUsedResources()) {
            val resource = candidate as? CustomResource ?: continue
            if (resource.isGlobal() && (resource.displayWhenZero || resource.appendResourceWhenThisZero)) {
                stored.ensureEntry(resource)
            }
        }
        stored.PENDING_sortValues()

        for (value in stored.values) {
            val entry = value as? StoredResource ?: continue
            val resource = entry.resource
            if (!resource.isHidden()) {
                appendNativeResourceLine(rows, team, resource, entry.amount, stored, 0, secondaryTeam)
            }
        }
    }
}

/** Pure row rules are shared with tests so native resource traversal keeps its exact display semantics. */
internal fun resourceHudSampleDue(lastSampleAt: Long, now: Long, intervalMillis: Long): Boolean =
    lastSampleAt == Long.MIN_VALUE || now - lastSampleAt >= intervalMillis

internal fun resourceHudRowsChanged(previous: List<AeroResourceHudLine>, current: List<AeroResourceHudLine>): Boolean =
    previous != current

internal fun resourceHudShouldRecurse(
    depth: Int,
    hasAppendedResource: Boolean,
    amount: Double,
    appendResourceWhenZero: Boolean,
): Boolean = depth < 6 && hasAppendedResource && (appendResourceWhenZero || amount != 0.0)

internal fun resourceHudShouldDisplay(
    amount: Double,
    displayWhenZero: Boolean,
    displayInHud: Boolean,
    depth: Int,
): Boolean = (amount != 0.0 || displayWhenZero) && (depth != 0 || displayInHud)

internal fun formatResourceHudLine(amountText: String, income: Int, expense: Int): String = buildString {
    append(amountText)
    if (expense != 0) {
        append("(+").append(income).append(")(-").append(expense).append(')')
    } else if (income != 0) {
        if (income >= 0) append("(+").append(income).append(')')
        else append('(').append(income).append(')')
    }
}

/** Native resource rows render appended values before their parent and allow six append levels. */
private fun appendNativeResourceLine(
    result: MutableList<AeroResourceHudLine>,
    team: Team,
    resource: CustomResource,
    amount: Double,
    stored: StoredResources,
    depth: Int,
    secondaryTeam: Boolean,
) {
    val appended = resource.appendResource
    if (resourceHudShouldRecurse(depth, appended != null, amount, resource.appendResourceWhenThisZero)) {
        val appendedResource = checkNotNull(appended)
        appendNativeResourceLine(
            result,
            team,
            appendedResource,
            stored.getAmount(appendedResource),
            stored,
            depth + 1,
            secondaryTeam,
        )
    }

    if (resourceHudShouldDisplay(amount, resource.displayWhenZero, resource.displayInHud, depth)) {
        result.add(
            AeroResourceHudLine(
                formatResourceHudLine(
                    resource.formatAmount(amount, false),
                    team.getResourceAmount(resource),
                    team.getNegativeResourceAmount(resource),
                ),
                resource.getDisplayColor(),
                secondaryTeam,
            )
        )
    }
}
