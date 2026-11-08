package io.github.rwpp.graphics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class AeroTeamPresentationTest {
    private val accent = Color(0xFF3CCDCF)

    @Test
    fun highlightColorsKeepTheNativeArgbValues() {
        // Unit.selectionPaintAlly and InterfaceEngine.resourceTextPaintAlt.
        assertEquals(0xB4FFFF00.toInt(), AeroAllyHighlight.toArgb())
        assertEquals(0xFFF0F000.toInt(), AeroAllyText.toArgb())
    }

    @Test
    fun ownTeamKeepsTheAccentAndTeammatesTurnYellow() {
        assertEquals(
            accent,
            aeroSelectionHighlightColor(
                ownTeam = true, enemy = false, observing = false, replay = false, accent = accent
            )
        )
        assertEquals(
            AeroAllyHighlight,
            aeroSelectionHighlightColor(
                ownTeam = false, enemy = false, observing = false, replay = false, accent = accent
            )
        )
    }

    @Test
    fun observerSelectionsStayYellowLikeWatchingSomeoneElse() {
        assertEquals(
            AeroAllyHighlight,
            aeroSelectionHighlightColor(
                ownTeam = true, enemy = false, observing = true, replay = false, accent = accent
            )
        )
        assertEquals(
            AeroAllyHighlight,
            aeroSelectionHighlightColor(
                ownTeam = false, enemy = false, observing = true, replay = false, accent = accent
            )
        )
    }

    @Test
    fun enemySelectionsStayRedAndReplayKeepsItsOwnPalette() {
        assertEquals(
            AeroEnemyHighlight,
            aeroSelectionHighlightColor(
                ownTeam = false, enemy = true, observing = true, replay = false, accent = accent
            )
        )
        assertEquals(
            accent,
            aeroSelectionHighlightColor(
                ownTeam = false, enemy = false, observing = false, replay = true, accent = accent
            )
        )
    }
}
