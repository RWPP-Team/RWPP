/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit as NativeUnit
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.land.Tank
import com.corrodinggames.rts.gameFramework.GameEngine
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import sun.misc.Unsafe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Calls the real JAR's private updateMoveThrottle and its native turnBodyTowards.
 * Only profile getters are supplied by the fixture: no controller code is copied.
 * This covers one controller step, not map/collision/pathfinding or position integration.
 */
class NativeMovePredictionTest {
    @Test
    fun actualTankControllerAgreesForFixedNearAndFarInputs() = withNativeEngine {
        val template = actualTankMotion()
        for (heading in listOf(-179f, -90f, -20f, 0f, 20f, 90f, 179f)) {
            for (distance in listOf(2f, 4f, 10f, 19f, 20f, 60f, 61f, 130f, 131f, 200f)) {
                for (terminal in listOf(false, true)) {
                    compare(template.copy(headingDegrees = heading, speedFraction = 0.5f), MovePoint(distance, 0f), terminal)
                }
            }
        }
    }

    @Test
    fun headingGateIncludesNativeDistanceAndSlowTurnThresholds() = withNativeEngine {
        val template = actualTankMotion().copy(maxTurnSpeed = 2f)
        for ((distance, gate) in listOf(10f to 20f, 20f to 46f, 61f to 89f)) {
            for (offset in listOf(-0.1f, 0f, 0.1f)) {
                compare(template.copy(headingDegrees = gate + offset), MovePoint(distance, 0f), true)
            }
        }
        for (turnSpeed in listOf(1f, 1.1f, 1.39f, 1.4f)) {
            for (distance in listOf(20f, 80f, 81f)) {
                compare(template.copy(maxTurnSpeed = turnSpeed, headingDegrees = 30f), MovePoint(distance, 0f), true)
            }
        }
    }

    @Test
    fun terminalBrakingUsesNativeDAndDoesNotApplyToEveryTankPoint() = withNativeEngine {
        val tank = actualTankMotion().copy(speedFraction = 1f)
        val tankResult = compare(tank, MovePoint(10f, 0f), true)
        assertEquals(1f, tankResult.moveThrottle, "Tank speed=1 and native D=0.17 do not enter early braking")

        for (deceleration in listOf(0.1f, 0.14f, 0.17f)) {
            for (distance in listOf(8f, 12f, 13f, 17f, 18f, 20f)) {
                for (terminal in listOf(false, true)) {
                    compare(tank.copy(maxSpeed = 2f, deceleration = deceleration), MovePoint(distance, 0f), terminal)
                }
            }
        }
    }

    @Test
    fun nonPositiveNativeTurnAccelerationRetainsStoredTurnRate() = withNativeEngine {
        for (acceleration in listOf(-1f, 0f)) {
            val motion = actualTankMotion().copy(
                headingDegrees = 30f,
                currentTurnRate = 0.8f,
                turnAcceleration = acceleration,
            )
            assertTrue(motion.valid, "Negative B means constant turn speed, not invalid profile")
            val native = compare(motion, MovePoint(200f, 0f), false)
            assertEquals(0.8f, native.currentTurnRate, "Native B<=0 branch does not overwrite stored turn rate")
        }
    }

    @Test
    fun stationaryFastUnitsUseOneDegreeGateAndFinalSpeedCaps() = withNativeEngine {
        val fast = actualTankMotion().copy(maxSpeed = 6f)
        for (heading in listOf(0.9f, 1f, 1.1f, 10f)) {
            compare(fast.copy(headingDegrees = heading), MovePoint(200f, 0f), true)
        }
        for (distance in listOf(8f, 9f, 17f, 18f, 20f)) {
            compare(fast.copy(speedFraction = 1f), MovePoint(distance, 0f), true)
        }
    }

    @Test
    fun reverseOnlyMatchesTheSupportedNonFormationMode() = withNativeEngine {
        val ordinary = actualTankMotion().copy(headingDegrees = 101f)
        val nativeTank = compare(ordinary, MovePoint(200f, 0f), true)
        assertFalse(nativeTank.reversing, "Tank's 0.6 reverse fraction does not enable reversal without formation conditions")

        for (heading in listOf(99.9f, 100f, 100.1f, 101f, 179f)) {
            compare(ordinary.copy(headingDegrees = heading, reverseSpeedFraction = 0.96f), MovePoint(200f, 0f), true)
        }
    }

    @Test
    fun aSingleUnitFormationLeaderUsesTheNativeRestrictedReverseRules() = withNativeEngine {
        val template = actualTankMotion().copy(headingDegrees = 120f, formationLeader = true)
        for (fraction in listOf(0.4f, 0.6f, 0.72f, 0.88f)) {
            for (distance in listOf(20f, 99f, 100f, 199f, 200f, 499f, 500f)) {
                compare(template.copy(reverseSpeedFraction = fraction), MovePoint(distance, 0f), true)
            }
        }
    }

    @Test
    fun repeatedNativeControllerStepsMatchWithoutUsingEstimatedWorldPosition() = withNativeEngine {
        var motion = actualTankMotion().copy(headingDegrees = 90f)
        val unit = nativeUnit(motion, MovePoint(200f, 0f), false)
        val directive = directive(MovePoint(200f, 0f))
        repeat(24) { tick ->
            throttle.invoke(unit, 1f, directive, null, true)
            val predicted = MoveRoutePlanner.predictStep(motion, MovePoint(200f, 0f), false)
            assertController(unit, predicted, "replay tick=$tick")
            // Native updateMoveThrottle does not integrate world position. Keep input fixed
            // and feed back only angle/rate/throttle for an independent controller recurrence.
            motion = predicted.copy(position = motion.position)
        }
    }

    private fun compare(motion: MoveMotion, target: MovePoint, terminal: Boolean): ProfileTank {
        val unit = nativeUnit(motion, target, terminal)
        throttle.invoke(unit, 1f, directive(target), null, true)
        val predicted = MoveRoutePlanner.predictStep(motion, target, terminal)
        assertController(unit, predicted, "heading=${motion.headingDegrees} distance=${motion.position.distanceTo(target)} " +
            "terminal=$terminal speed=${motion.maxSpeed} turn=${motion.maxTurnSpeed} B=${motion.turnAcceleration}")
        return unit
    }

    private fun assertController(native: Tank, predicted: MoveMotion, detail: String) {
        val angularError = ((predicted.headingDegrees - native.angle + 540f) % 360f) - 180f
        assertTrue(abs(angularError) < 0.0002f, "$detail: angle native=${native.angle} predicted=${predicted.headingDegrees}")
        assertEquals(native.currentTurnRate, predicted.currentTurnRate, 0.00001f, "$detail: turn rate")
        assertEquals(native.moveThrottle, predicted.speedFraction, 0.00001f, "$detail: throttle")
    }

    private fun actualTankMotion(): MoveMotion {
        val tank = unsafe.allocateInstance(Tank::class.java) as Tank
        return MoveMotion(
            position = MovePoint(0f, 0f), headingDegrees = 0f, speedFraction = 0f, currentTurnRate = 0f,
            maxSpeed = tank.moveSpeed, maxTurnSpeed = tank.turnSpeed, turnAcceleration = tank.turnAcceleration,
            acceleration = tank.moveAccelerationSpeed, deceleration = tank.buildSpeed,
            reverseSpeedFraction = tank.reverseSpeedPercentage, radius = 11f,
        )
    }

    private fun nativeUnit(motion: MoveMotion, target: MovePoint, terminal: Boolean): ProfileTank {
        val unit = unsafe.allocateInstance(ProfileTank::class.java) as ProfileTank
        unit.profile = motion
        unit.x = motion.position.x
        unit.y = motion.position.y
        unit.angle = motion.headingDegrees
        unit.moveThrottle = motion.speedFraction
        unit.currentTurnRate = motion.currentTurnRate
        unit.inFormation = motion.formationLeader
        unit.formationSlotCount = 1
        field(OrderableUnit::class.java, "hasMoveTarget").setBoolean(unit, true)
        val count = if (terminal) 1 else 2
        field(OrderableUnit::class.java, "activePathCount").setInt(unit, count)
        field(OrderableUnit::class.java, "pathNodeTotal").setInt(unit, count)
        val nodes = field(OrderableUnit::class.java, "pathNodes")
        nodes.set(unit, ReflectArray.newInstance(nodes.type.componentType, 32))
        unit.setPathNode(0, target.x, target.y)
        unit.setPathNode(1, target.x + 500f, target.y)
        val turrets = field(NativeUnit::class.java, "turrets")
        val turretArray = ReflectArray.newInstance(turrets.type.componentType, 10)
        repeat(10) { ReflectArray.set(turretArray, it, unsafe.allocateInstance(turrets.type.componentType)) }
        turrets.set(unit, turretArray)
        return unit
    }

    private fun directive(target: MovePoint): Any = unsafe.allocateInstance(directiveType).apply {
        field(directiveType, "hasMoveTarget").setBoolean(this, true)
        field(directiveType, "targetX").setFloat(this, target.x)
        field(directiveType, "targetY").setFloat(this, target.y)
    }

    /** Only scalar profile access differs from Tank; all native movement methods remain unchanged. */
    private class ProfileTank private constructor() : Tank(true) {
        lateinit var profile: MoveMotion
        override fun getUnitTypeInterface(): com.corrodinggames.rts.game.units.UnitTypeInterface =
            com.corrodinggames.rts.game.units.UnitType.tank
        override fun getMoveSpeed() = profile.maxSpeed
        override fun getTurnSpeed() = profile.maxTurnSpeed
        override fun getTurnAcceleration() = profile.turnAcceleration
        override fun getMoveAccelerationSpeed() = profile.acceleration
        override fun getBuildSpeed() = profile.deceleration
        override fun getReverseSpeedPercentage() = profile.reverseSpeedFraction
    }

    private companion object {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val engine = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val directiveType = Class.forName("com.corrodinggames.rts.game.units.MoveDirective")
        val throttle = OrderableUnit::class.java.getDeclaredMethod(
            "updateMoveThrottle", Float::class.javaPrimitiveType, directiveType, Waypoint::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        fun field(owner: Class<*>, name: String): Field = owner.getDeclaredField(name).apply { isAccessible = true }

        fun <T> withNativeEngine(block: () -> T): T {
            val previous = engine.get(null)
            engine.set(null, unsafe.allocateInstance(RWGameEngine::class.java))
            return try { block() } finally { engine.set(null, previous) }
        }
    }
}
