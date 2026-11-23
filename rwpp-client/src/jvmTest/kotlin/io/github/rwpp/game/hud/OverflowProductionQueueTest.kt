package io.github.rwpp.game.hud

import io.github.rwpp.game.hud.OverflowProductionQueue.Admission
import io.github.rwpp.game.hud.OverflowProductionQueue.AdmissionInput
import io.github.rwpp.game.hud.OverflowProductionQueue.classify
import kotlin.test.Test
import kotlin.test.assertEquals

class OverflowProductionQueueTest {
    private fun input(
        unlocked: Boolean = true,
        built: Boolean = true,
        speed: Boolean = true,
        transport: Boolean = false,
        cap: Boolean = false,
        affordable: Boolean = false,
        onlyResource: Boolean = false,
    ) = AdmissionInput(
        unlocked = unlocked,
        builtAndNotTransported = built,
        factorySpeedPositive = speed,
        overTransportCapacity = transport,
        unitCapReached = cap,
        affordable = affordable,
        onlyResourceShortage = onlyResource,
    )

    @Test
    fun resourceShortageDeferredAndTransientGatesWaitInsteadOfDropping() {
        assertEquals(Admission.Resources, classify(input(onlyResource = true)))
        assertEquals(Admission.Resources, classify(input(onlyResource = true, transport = true)))
        assertEquals(Admission.Waiting, classify(input(transport = true, affordable = true)))
        assertEquals(Admission.Waiting, classify(input(cap = true, affordable = true)))
        assertEquals(Admission.Waiting, classify(input(built = false, affordable = true)))
        assertEquals(Admission.Waiting, classify(input(speed = false, affordable = true)))
    }

    @Test
    fun readyAndPermanentFailuresAreDistinguished() {
        assertEquals(Admission.Ready, classify(input(affordable = true)))
        assertEquals(Admission.Waiting, classify(input(affordable = true, transport = true)))
        assertEquals(Admission.Blocked, classify(input(unlocked = false, onlyResource = true)))
        assertEquals(Admission.Blocked, classify(input()))
    }
}
