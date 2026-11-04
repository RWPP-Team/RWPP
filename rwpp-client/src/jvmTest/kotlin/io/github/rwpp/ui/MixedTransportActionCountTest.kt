package io.github.rwpp.ui

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.units.actions.EditorActionButton
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.air.Dropship
import com.corrodinggames.rts.game.units.land.Hovercraft
import com.corrodinggames.rts.game.units.land.Tank
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.game.hud.hudActionCountForUnit
import io.github.rwpp.game.hud.hudActionLabelForUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import com.corrodinggames.rts.game.units.Unit as NativeUnit

class MixedTransportActionCountTest {
    @Test
    fun mixedActionCaptionUsesAlreadyResolvedCountsWithoutNativeGlobalAggregation() {
        val action = object : EditorActionButton(900001) {
            override fun getDisplayName(): String = "Transport"
            override fun getDisplayDescription(): String = ""
            override fun getUnitType(): com.corrodinggames.rts.game.units.UnitTypeInterface? = null
            override fun compareTo(other: Any?): Int = 0
            override fun getCountForUnit(unit: NativeUnit, queued: Boolean): Int =
                error("Must use owner-specific counts")

            override fun getDisplayNameWithCount(): String = error("Unsafe native mixed aggregation")
        }
        assertEquals("Transport (3)", hudActionLabelForUnit(action, null, 3))
    }

    @Test
    fun unloadCountsUseEachNativeTransportImplementationInMixedSelections() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engineField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previous = engineField.get(null)
        engineField.set(null, unsafe.allocateInstance(RWGameEngine::class.java))
        try {
            val dropship = unsafe.allocateInstance(Dropship::class.java) as Dropship
            val hovercraft = unsafe.allocateInstance(Hovercraft::class.java) as Hovercraft
            val tank = unsafe.allocateInstance(Tank::class.java) as Tank
            for ((owner, cargoCount) in listOf(dropship to 1, hovercraft to 2)) {
                val cargo = FastArrayList().apply { repeat(cargoCount) { add(tank) } }
                owner.javaClass.getDeclaredField("transportedUnits").apply { isAccessible = true }.set(owner, cargo)
            }
            val unload = dropship.specialActions.filterIsInstance<SpecialAction>()
                .first { it.getCountForUnit(dropship, true) == 1 }
            assertEquals(1, hudActionCountForUnit(unload, dropship, true))
            assertEquals(2, hudActionCountForUnit(unload, hovercraft, true))
            assertEquals(0, hudActionCountForUnit(unload, tank, true))
        } finally {
            engineField.set(null, previous)
        }
    }
}
