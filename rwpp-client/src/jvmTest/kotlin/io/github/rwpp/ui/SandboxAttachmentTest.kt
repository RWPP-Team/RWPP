package io.github.rwpp.ui

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadata
import com.corrodinggames.rts.game.units.custom.DynamicMetadata
import com.corrodinggames.rts.game.units.custom.components.UnitAttachmentsAttachmentSlot
import com.corrodinggames.rts.game.units.land.Builder
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.game.sandbox.SandboxAttachmentRejection
import io.github.rwpp.game.sandbox.sandboxAttachmentRejection
import io.github.rwpp.game.sandbox.sandboxAttachmentSlotFromPayload
import io.github.rwpp.game.sandbox.sandboxAttachmentSlotPayload
import kotlin.test.*

class SandboxAttachmentTest {
    @Test
    fun parentChainValidationRejectsSelfCyclesAlreadyAttachedAndBrokenCycles() {
        val host = customUnit(101L)
        val child = customUnit(102L)
        val grandchild = customUnit(103L)

        assertEquals(SandboxAttachmentRejection.SAME_UNIT, sandboxAttachmentRejection(host, host))
        assertEquals(SandboxAttachmentRejection.NONE, sandboxAttachmentRejection(host, child))

        host.attachedToUnit = child
        assertEquals(SandboxAttachmentRejection.CYCLE, sandboxAttachmentRejection(host, child))
        host.attachedToUnit = null

        child.attachedToUnit = host
        assertEquals(SandboxAttachmentRejection.CHILD_ALREADY_ATTACHED, sandboxAttachmentRejection(host, child))
        assertEquals(SandboxAttachmentRejection.CYCLE, sandboxAttachmentRejection(child, host))

        child.attachedToUnit = grandchild
        grandchild.attachedToUnit = child
        val corruptHost = customUnit(105L).apply { attachedToUnit = child }
        assertEquals(
            SandboxAttachmentRejection.CORRUPT_PARENT_CHAIN,
            sandboxAttachmentRejection(corruptHost, customUnit(106L)),
        )
    }

    @Test
    fun nativeSlotAcceptsAnExistingVanillaOrderableUnitAndNativeDetachClearsTheLink() = withNativeGameEngine {
        val slot = attachmentSlot(0, "install")
        val host = customUnit(201L).apply { meta = customMetadata(slot) }
        val child = unsafe.allocateInstance(Builder::class.java) as OrderableUnit
        child.id = 202L

        assertEquals(SandboxAttachmentRejection.NONE, sandboxAttachmentRejection(host, child))
        assertTrue(host.attachRequest(child, slot), "native CustomUnit attachment slots do not whitelist child types")
        assertSame(host, child.attachedToUnit)
        assertSame(child, host.getUnitInAttachmentSlot(slot))
        assertEquals(SandboxAttachmentRejection.CHILD_ALREADY_ATTACHED, sandboxAttachmentRejection(host, child))

        assertTrue(host.deattachRequest(child))
        assertNull(child.attachedToUnit)
        assertNull(host.getUnitInAttachmentSlot(slot))
    }

    @Test
    fun nativeWaypointCarriesUnitTypeAndSlotPayloadWithoutAnArrayIndexIdentity() = withNativeGameEngine {
        val slotPayload = sandboxAttachmentSlotPayload(7) ?: error("valid slot rejected")
        assertEquals(7, sandboxAttachmentSlotFromPayload(slotPayload))
        assertNull(sandboxAttachmentSlotFromPayload(Float.NaN))
        assertNull(sandboxAttachmentSlotFromPayload(1.5f))
        assertNull(sandboxAttachmentSlotPayload(-1))

        val teamArrayField = Team::class.java.getDeclaredField("teamArray").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val teamArray = teamArrayField.get(null) as Array<Team?>
        val previousTeam = teamArray[0]
        val team = unsafe.allocateInstance(Player::class.java) as Player
        team.teamId = 0
        teamArray[0] = team
        try {
            val command = CommandControllerCommand(null).apply {
                this.team = team
                actionId = ActionId.EMPTY
                hasSystemAction = true
                systemActionId = 827301
                PENDING_systemCommandFloat = slotPayload
                setBuildWaypoint(32f, 48f, UnitType.tank, 0)
            }
            val output = OutputNetStream()
            command.write(output)
            val restored = CommandControllerCommand(null).apply { read(InputNetStream(output.toByteArray())) }

            assertTrue(restored.hasSystemAction)
            assertEquals(command.systemActionId, restored.systemActionId)
            assertEquals(slotPayload, restored.PENDING_systemCommandFloat)
            assertEquals("tank", restored.waypoint?.unitType?.internalName)
            assertEquals(32f, restored.waypoint?.targetX)
            assertEquals(48f, restored.waypoint?.targetY)
        } finally {
            teamArray[0] = previousTeam
        }
    }

    private companion object {
        val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as sun.misc.Unsafe

        fun customUnit(id: Long): CustomUnit =
            (unsafe.allocateInstance(CustomUnit::class.java) as CustomUnit).apply {
                this.id = id
                unitData = DynamicMetadata(true)
                maxHp = 100f
                hp = 100f
                val unit = this
                CustomUnit::class.java.getDeclaredField("transportedUnits").also { field ->
                    field.isAccessible = true
                    field.set(unit, FastArrayList())
                }
            }

        fun customMetadata(vararg slots: UnitAttachmentsAttachmentSlot): CustomUnitMetadata =
            (unsafe.allocateInstance(CustomUnitMetadata::class.java) as CustomUnitMetadata).also { metadata ->
                CustomUnitMetadata::class.java.getDeclaredField("attachmentSlots").apply {
                    isAccessible = true
                    set(metadata, FastArrayList().apply { slots.forEach(::add) })
                }
                CustomUnitMetadata::class.java.getDeclaredField("eventTriggers").also { field ->
                    field.isAccessible = true
                    field.set(metadata, FastArrayList())
                }
            }

        fun attachmentSlot(index: Int, name: String): UnitAttachmentsAttachmentSlot {
            val slot = UnitAttachmentsAttachmentSlot()
            UnitAttachmentsAttachmentSlot::class.java.getDeclaredField("slotIndex").apply {
                isAccessible = true
                setShort(slot, index.toShort())
            }
            UnitAttachmentsAttachmentSlot::class.java.getDeclaredField("name").apply {
                isAccessible = true
                set(slot, name)
            }
            return slot
        }

        fun <T> withNativeGameEngine(block: () -> T): T {
            val engineField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
            val previousEngine = engineField.get(null)
            engineField.set(null, unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java))
            try {
                return block()
            } finally {
                engineField.set(null, previousEngine)
            }
        }
    }
}
