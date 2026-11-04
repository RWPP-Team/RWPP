package io.github.rwpp.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnitAppearanceColorsTest {
    @Test
    fun sourcesAcceptPositionSlotsAndBothHexWidths() {
        assertEquals(UnitAppearanceColorSource.Position, parseUnitAppearanceColorSource(" POSITION "))
        assertEquals(UnitAppearanceColorSource.PlayerPosition(0), parseUnitAppearanceColorSource("1"))
        assertEquals(UnitAppearanceColorSource.PlayerPosition(9), parseUnitAppearanceColorSource("10"))
        assertEquals(UnitAppearanceColorSource.Fixed(0xFF12AB34.toInt()), parseUnitAppearanceColorSource("#12ab34"))
        assertEquals(UnitAppearanceColorSource.Fixed(0x8012AB34.toInt()), parseUnitAppearanceColorSource("#8012AB34"))
        assertNull(parseUnitAppearanceColorSource("0"))
        assertNull(parseUnitAppearanceColorSource("+1"))
        assertNull(parseUnitAppearanceColorSource("#12345"))
        assertNull(parseUnitAppearanceColorSource("#GG0000"))
        assertNull(parseUnitAppearanceColorSource("#+00000"))
    }

    @Test
    fun playerMapUsesOwnerPositionAndFallsBackSafely() {
        val parsed = parseUnitAppearancePlayerColorMap("10, #123456, position")
        assertEquals(UnitAppearanceColorSource.PlayerPosition(9), parsed[0])
        assertEquals(UnitAppearanceColorSource.Fixed(0xFF123456.toInt()), parsed[1])
        assertEquals(UnitAppearanceColorSource.Position, parsed[2])
        assertEquals(UnitAppearanceColorSource.Position, parsed[3])
        assertEquals(10, parsed.size)
    }

    @Test
    fun paintIsOptInAndTriColorKeepsNativePositionSources() {
        val map = parseUnitAppearancePlayerColorMap("1,2,3,4,5,6,7,8,9,10")
        val native = listOf(101, 202, 303, 404, 505, 606, 707, 808, 909, 1010)
        val nativeColor: (Int) -> Int = native::get
        assertNull(
            resolveUnitAppearancePaintColor(
                enabled = false,
                triColorEnabled = true,
                relation = UnitAppearanceRelation.SELF,
                ownerPosition = 2,
                ownerNativeColor = 303,
                playerColorMap = map,
                selfSource = UnitAppearanceColorSource.Fixed(1),
                enemySource = UnitAppearanceColorSource.Fixed(2),
                allySource = UnitAppearanceColorSource.Fixed(3),
                nativeColorForPlayerPosition = nativeColor,
            )
        )
        assertEquals(
            0xFFAABBCC.toInt(),
            resolveUnitAppearancePaintColor(
                enabled = true,
                triColorEnabled = true,
                relation = UnitAppearanceRelation.ENEMY,
                ownerPosition = 2,
                ownerNativeColor = 303,
                playerColorMap = map,
                selfSource = UnitAppearanceColorSource.Fixed(1),
                enemySource = UnitAppearanceColorSource.Fixed(0xFFAABBCC.toInt()),
                allySource = UnitAppearanceColorSource.Fixed(3),
                nativeColorForPlayerPosition = nativeColor,
            )
        )
        assertEquals(
            303,
            resolveUnitAppearancePaintColor(
                enabled = true,
                triColorEnabled = false,
                relation = UnitAppearanceRelation.ENEMY,
                ownerPosition = 2,
                ownerNativeColor = 303,
                playerColorMap = map,
                selfSource = UnitAppearanceColorSource.Fixed(1),
                enemySource = UnitAppearanceColorSource.Fixed(2),
                allySource = UnitAppearanceColorSource.Fixed(3),
                nativeColorForPlayerPosition = nativeColor,
            )
        )
    }

    @Test
    fun ownerPositionSelectsTheMapRowAndNumbersReferenceActualPlayers() {
        val nativePlayerColors = listOf(
            0xFF223344.toInt(), // player position 1 currently has native display palette 2
            0xFFABCDEF.toInt(), // player position 2 has a changed color override
            0xFF556677.toInt(),
        )
        val playerColor: (Int) -> Int = { position -> nativePlayerColors.getOrElse(position) { -1 } }
        val rowZeroCustom = parseUnitAppearancePlayerColorMap("#CC112233, #DD445566")[0]

        assertEquals(
            0xCC112233.toInt(),
            resolveUnitAppearancePaintColor(
                enabled = true,
                triColorEnabled = false,
                relation = UnitAppearanceRelation.SELF,
                ownerPosition = 0,
                ownerNativeColor = nativePlayerColors[0],
                playerColorMap = listOf(rowZeroCustom, UnitAppearanceColorSource.Fixed(0xDD445566.toInt())),
                selfSource = UnitAppearanceColorSource.Position,
                enemySource = UnitAppearanceColorSource.Position,
                allySource = UnitAppearanceColorSource.Position,
                nativeColorForPlayerPosition = playerColor,
            )
        )

        assertEquals(
            nativePlayerColors[1],
            resolveUnitAppearancePaintColor(
                enabled = true,
                triColorEnabled = false,
                relation = UnitAppearanceRelation.SELF,
                ownerPosition = 0,
                ownerNativeColor = nativePlayerColors[0],
                playerColorMap = parseUnitAppearancePlayerColorMap("2"),
                selfSource = UnitAppearanceColorSource.Position,
                enemySource = UnitAppearanceColorSource.Position,
                allySource = UnitAppearanceColorSource.Position,
                nativeColorForPlayerPosition = playerColor,
            )
        )

        assertEquals(
            nativePlayerColors[0],
            resolveUnitAppearancePaintColor(
                enabled = true,
                triColorEnabled = false,
                relation = UnitAppearanceRelation.SELF,
                ownerPosition = 0,
                ownerNativeColor = nativePlayerColors[0],
                playerColorMap = parseUnitAppearancePlayerColorMap("position"),
                selfSource = UnitAppearanceColorSource.Position,
                enemySource = UnitAppearanceColorSource.Position,
                allySource = UnitAppearanceColorSource.Position,
                nativeColorForPlayerPosition = playerColor,
            )
        )
    }

    @Test
    fun neutralAndPlayersOutsideTenKeepTheirNativeColors() {
        val ownerNative = 0xFF998877.toInt()
        val map = List(10) { UnitAppearanceColorSource.Fixed(0xFF000000.toInt()) }
        for ((position, relation) in listOf(
            -1 to UnitAppearanceRelation.NEUTRAL,
            10 to UnitAppearanceRelation.ALLY,
        )) {
            assertEquals(
                ownerNative,
                resolveUnitAppearancePaintColor(
                    enabled = true,
                    triColorEnabled = true,
                    relation = relation,
                    ownerPosition = position,
                    ownerNativeColor = ownerNative,
                    playerColorMap = map,
                    selfSource = UnitAppearanceColorSource.Fixed(1),
                    enemySource = UnitAppearanceColorSource.Fixed(2),
                    allySource = UnitAppearanceColorSource.Fixed(3),
                    nativeColorForPlayerPosition = { 999 },
                )
            )
        }
    }

    @Test
    fun selectionRingToggleIsIndependentAndIncludesNeutral() {
        assertNull(resolveUnitAppearanceRingColor(false, UnitAppearanceRelation.SELF, 1, 2, 3, 4))
        assertEquals(4, resolveUnitAppearanceRingColor(true, UnitAppearanceRelation.NEUTRAL, 1, 2, 3, 4))
    }
}
