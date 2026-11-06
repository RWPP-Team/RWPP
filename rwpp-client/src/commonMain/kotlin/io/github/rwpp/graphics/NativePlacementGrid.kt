/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

/** World-space bounds of one native green placement grid, in map pixels. */
internal data class NativePlacementTile(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val stepX: Float,
    val stepY: Float,
)

/**
 * Map.drawBuildingPlacement draws the native placement squares after tile snapping,
 * resource-pool adjustment and footprint expansion. Aero samples those bounds so its
 * build effect lands on the same tiles instead of inverse-projecting the raw pointer.
 */
/** Chains the placement squares in native drag order, like an already queued build. */
internal fun placementChain(
    source: androidx.compose.ui.geometry.Offset,
    cells: List<NativePlacementTile>,
): List<Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset>> {
    var from = source
    return cells.map { cell ->
        val center = androidx.compose.ui.geometry.Offset(
            (cell.left + cell.right) / 2f,
            (cell.top + cell.bottom) / 2f,
        )
        val link = from to center
        from = center
        link
    }
}

internal object NativePlacementGrid {
    private val lock = Any()
    private val pending = ArrayList<NativePlacementTile>(8)

    @Volatile
    private var published: List<NativePlacementTile> = emptyList()

    @Volatile
    private var publishedAt = 0L

    /**
     * InterfaceEngine.update marks the start of a native frame. Publishing the
     * previous frame here means a new frame always replaces every square from the
     * old pointer position, and sampling never sees a half-built placement drag.
     */
    fun beginFrame() {
        synchronized(lock) {
            published = if (pending.isEmpty()) emptyList() else pending.toList()
            pending.clear()
        }
        publishedAt = System.nanoTime()
    }

    /** Map.drawBuildingPlacement reports one square of the current frame. */
    fun record(tile: NativePlacementTile) {
        synchronized(lock) { pending.add(tile) }
    }

    fun snapshot(): List<NativePlacementTile> {
        if (publishedAt == 0L || System.nanoTime() - publishedAt > 250_000_000L) return emptyList()
        return published
    }
}
