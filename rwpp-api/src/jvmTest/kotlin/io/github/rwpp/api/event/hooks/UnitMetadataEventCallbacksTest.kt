package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.units.custom.CustomUnitMetadataMissingUnitInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitMetadataEventCallbacksTest {
    @Test
    fun nativeMissingUnitCountersUseTheirMapKeysWithoutReflectingModName() {
        val native = hashMapOf<Any?, Any?>(
            "Zeta mod" to CustomUnitMetadataMissingUnitInfo("Zeta mod"),
            "Alpha mod" to CustomUnitMetadataMissingUnitInfo("Alpha mod"),
            null to CustomUnitMetadataMissingUnitInfo(null),
        )
        assertEquals(listOf("Alpha mod", "Zeta mod"), requiredModNames(native))
    }

    @Test
    fun unknownCounterImplementationsAndMalformedKeysCannotBreakTheNativeMismatchReport() {
        assertEquals(
            listOf("valid mod"), requiredModNames(
                hashMapOf(
                    "valid mod" to Any(), "  " to Any(), 12 to Any(), null to Any(),
                )
            )
        )
        assertEquals(emptyList(), requiredModNames(emptyMap<Any, Any>()))
    }
}
