package io.github.rwpp.game.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FactoryEconomyRequirementTest {
    @Test
    fun multipliesByEligibleFactoryCount() {
        // 300 / 10s * 0.66 = 19.8 -> needs 20; two factories double it.
        assertEquals(20, factoryEconomyRequirement(300.0, 10.0, 1))
        assertEquals(40, factoryEconomyRequirement(300.0, 10.0, 2))
    }

    @Test
    fun rejectsUnusableInputs() {
        assertNull(factoryEconomyRequirement(300.0, 10.0, 0))
        assertNull(factoryEconomyRequirement(0.0, 10.0, 1))
        assertNull(factoryEconomyRequirement(300.0, 0.0, 1))
        assertNull(factoryEconomyRequirement(Double.NaN, 10.0, 1))
    }

    @Test
    fun parsesNativeQueueTimeFormats() {
        assertEquals(90.0, parseQueueTimeSeconds("Build time: 1:30"))
        assertEquals(3723.0, parseQueueTimeSeconds("1:02:03"))
        assertEquals(45.0, parseQueueTimeSeconds("45s"))
        assertEquals(12.5, parseQueueTimeSeconds("12.5 sec"))
        assertEquals(90.0, parseQueueTimeSeconds("1m 30s"))
        assertEquals(20.0, parseQueueTimeSeconds("建造时间：20秒"))
        assertEquals(7.0, parseQueueTimeSeconds("7"))
        assertNull(parseQueueTimeSeconds(""))
        assertNull(parseQueueTimeSeconds(null))
        assertNull(parseQueueTimeSeconds("instant"))
    }
}
