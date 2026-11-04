package io.github.rwpp.ui

import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import io.github.rwpp.graphics.aeroAttackRangeAllowed
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnitRangeSelectionTest {
    @Test
    fun attackRangesUseVanillaTurretMinimumBeforeUnitAndBuildingOverrides() {
        val land = com.corrodinggames.rts.game.units.MovementType.LAND

        assertFalse(aeroAttackRangeAllowed(164.99f, land, false, "All", true, true))
        assertTrue(aeroAttackRangeAllowed(165f, land, false, "All", false, false))
        assertFalse(aeroAttackRangeAllowed(164f, land, true, "Never", true, true))
        assertFalse(aeroAttackRangeAllowed(165f, land, true, "Never", false, false))
        assertTrue(aeroAttackRangeAllowed(165f, land, true, "Never", true, false))
        assertFalse(aeroAttackRangeAllowed(Float.NaN, land, false, "All", true, true))
    }

    @Test
    fun individualRangeTogglesDoNotChangeAnyOtherUnitAndReleaseDeadUnits() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe

        fun unit() = unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Tank::class.java)
                as com.corrodinggames.rts.game.units.Unit

        val first = unit();
        val second = unit()
        val ranges = EntityRangeUnitComp()
        assertTrue(ranges.toggleAttackRange(first))
        assertTrue(ranges.isAttackRangeShown(first))
        assertFalse(ranges.isAttackRangeShown(second))
        assertTrue(ranges.toggleAttackRange(second))
        assertFalse(ranges.toggleAttackRange(first))
        assertFalse(ranges.isAttackRangeShown(first))
        assertTrue(ranges.isAttackRangeShown(second))
        second.dead = true
        assertFalse(ranges.hasExplicitRanges())
    }
}
