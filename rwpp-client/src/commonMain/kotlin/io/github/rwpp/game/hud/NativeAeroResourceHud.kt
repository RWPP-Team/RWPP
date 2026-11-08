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
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.game.replay.aeroPresentationTeam

/** One resource in an appendResourceInHUD chain, keeping its own native colour and icon. */
internal data class AeroResourceHudSegment(
    val text: String,
    val displayColor: Int?,
    val icon: BitmapOrTexture? = null,
    val iconId: Int = -1,
    val iconVersion: Int = 0,
)

internal data class AeroResourceHudLine(
    val text: String,
    val displayColor: Int?,
    val secondaryTeam: Boolean,
    /** Chained resources share one row; each segment keeps its own colour and icon. */
    val segments: List<AeroResourceHudSegment> = emptyList(),
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
        // An observed team is somebody else's economy, so the rows keep the native alt colour.
        val observing = io.github.rwpp.graphics.aeroObservingTeam(engine)
        var firstTeam: Team? = null
        if (engine.replayEngine.isPlayingReplay() || player?.isSpectator() == true) {
            val selected: OrderableUnit? = ui.getFirstSelectedOrderableUnit()
            val team = selected?.team
            if (team != null) {
                firstTeam = team
                appendTeamRows(team, secondaryTeam = observing)
            }
        } else if (player != null) {
            if (player != Team.grayTeam && !player.isSpectator()) {
                firstTeam = player
                appendTeamRows(player, secondaryTeam = observing)
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

    /**
     * Mirrors InterfaceEngine.drawTeamResources/drawStoredResources. Credits are already the first
     * line of the status readout, so only the stored resource rows belong here. Native lays a whole
     * appendResourceInHUD chain out on one horizontal row through resourceColumnUsedWidth, so every
     * chain becomes one line instead of one line per resource.
     */
    private fun appendTeamRows(team: Team, secondaryTeam: Boolean) {
        stored.copyFrom(team.getStoredResourcesAlias())
        for (candidate in CustomResource.getUsedResources()) {
            val resource = candidate as? CustomResource ?: continue
            if (resource.isGlobal() && (resource.displayWhenZero || resource.appendResourceWhenThisZero)) {
                stored.ensureEntry(resource)
            }
        }
        stored.PENDING_sortValues()

        val entries = ArrayList<StoredResource>(stored.values.size)
        val chained = HashSet<CustomResource>()
        for (value in stored.values) {
            val entry = value as? StoredResource ?: continue
            entries.add(entry)
            if (!entry.resource.isHidden()) entry.resource.appendResource?.let { chained.add(it) }
        }
        for (entry in entries) {
            val resource = entry.resource
            if (resource.isHidden() || resource in chained) continue
            appendResourceGroup(rows, team, resource, entry.amount, stored, secondaryTeam)
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

/** Follows appendResourceInHUD and emits exactly one row for the whole chain. */
private fun appendResourceGroup(
    result: MutableList<AeroResourceHudLine>,
    team: Team,
    root: CustomResource,
    rootAmount: Double,
    stored: StoredResources,
    secondaryTeam: Boolean,
) {
    val segments = ArrayList<AeroResourceHudSegment>(4)
    var resource: CustomResource? = root
    var amount = rootAmount
    var depth = 0
    while (resource != null) {
        val current = resource
        if (resourceHudShouldDisplay(amount, current.displayWhenZero, current.displayInHud, depth)) {
            // InterfaceEngine.drawResourceEntry uses formatAmount(amount, true): the prefix is the
            // localised displayPrefixInHUD/displayTextPrefix (or displayName), never an internal key.
            // formatAmountWithLabel belongs to unit descriptions and leaks names like "g_point".
            val icon = current.getIconImage()
            segments.add(
                AeroResourceHudSegment(
                    formatResourceHudLine(
                        current.formatAmount(amount, true),
                        team.getResourceAmount(current),
                        team.getNegativeResourceAmount(current),
                    ),
                    current.getDisplayColor(),
                    icon,
                    icon?.id ?: -1,
                    icon?.version ?: 0,
                )
            )
        }
        val next = current.appendResource
        if (!resourceHudShouldRecurse(depth, next != null, amount, current.appendResourceWhenThisZero)) break
        resource = next
        amount = stored.getAmount(checkNotNull(next))
        depth++
    }
    if (segments.isEmpty()) return
    result.add(
        AeroResourceHudLine(
            segments.joinToString("") { it.text },
            segments.first().displayColor,
            secondaryTeam,
            segments,
        )
    )
}
