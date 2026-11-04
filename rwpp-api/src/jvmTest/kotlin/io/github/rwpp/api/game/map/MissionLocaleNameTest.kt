package io.github.rwpp.api.game.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MissionLocaleNameTest {
    @Test
    fun retainsMissionNumberAndTitleForNativeTranslationLookup() {
        assertEquals(
            "mission_1__-__Dividing_River", missionLocaleMapName(
                "maps/normal/l010;[demo]mission_1__-__Dividing_River.tmx"
            )
        )
        assertEquals("challenge_1", missionLocaleMapName("maps\\challenge\\l020;challenge_1.TMX"))
    }
}
