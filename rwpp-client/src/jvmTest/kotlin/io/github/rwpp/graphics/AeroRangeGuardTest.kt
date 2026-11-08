/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AeroRangeGuardTest {
    @Test
    fun unlimitedBuildRangeIsRejectedBeforeItBecomesGeometry() {
        val groups = buildAeroRangeGroups(
            listOf(
                AeroRangeCircle(Offset(100f, 100f), 900_000f, 1, Color.Blue, "build"),
                AeroRangeCircle(Offset(100f, 100f), Float.POSITIVE_INFINITY, 1, Color.Blue, "build"),
                AeroRangeCircle(Offset(100f, 100f), 20f, 1, Color.Blue, "attack"),
            )
        )
        assertEquals(1, groups.size)
        assertEquals("attack", groups.single().kind)
        assertEquals(20f, groups.single().circles.single().radius)
    }

    @Test
    fun trackerAlsoDropsAnUnlimitedRangeWhenItsOwnerMoves() {
        val tracker = AeroRangeTracker<String>()
        tracker.add("builder", AeroRangeCircle(Offset.Zero, 900_000f, 1, Color.Blue, "build"))
        tracker.add("turret", AeroRangeCircle(Offset.Zero, 30f, 1, Color.Blue, "attack"))
        val groups = tracker.frame { Offset(5f, 5f) }
        assertEquals(1, groups.size)
        assertEquals("attack", groups.single().kind)
    }

    @Test
    fun worldRadiusLimitMatchesTheNativeUnlimitedThreshold() {
        assertTrue(AERO_RANGE_MAX_WORLD_RADIUS >= 800_000f)
        assertEquals(8f, AERO_RANGE_MAX_PROJECTED_RADIUS_FACTOR)
    }
}
