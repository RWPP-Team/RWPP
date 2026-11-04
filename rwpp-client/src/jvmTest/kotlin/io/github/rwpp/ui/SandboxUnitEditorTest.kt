package io.github.rwpp.ui

import android.graphics.PointF
import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadata
import com.corrodinggames.rts.game.units.custom.DynamicMetadata
import com.corrodinggames.rts.game.units.custom.resources.CustomResource
import com.corrodinggames.rts.game.units.custom.resources.ResourceReference
import com.corrodinggames.rts.game.units.custom.resources.StoredResources
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.config.Settings
import io.github.rwpp.game.sandbox.*
import io.github.rwpp.logic.config.migrateTerrainEdgeBuildingPreference
import kotlin.test.*

class SandboxUnitEditorTest {
    @Test
    fun directUnitEditsAreRestrictedToLocalSandboxOrOfflineReplay() {
        assertTrue(
            sandboxUnitDataUiAllowed(
                singlePlayer = true,
                networked = false,
                replaying = false,
                sandboxMode = true,
                debugEditor = false
            )
        )
        assertTrue(
            sandboxUnitDataUiAllowed(
                singlePlayer = true,
                networked = false,
                replaying = false,
                sandboxMode = false,
                debugEditor = true
            )
        )
        assertFalse(
            sandboxUnitDataUiAllowed(
                singlePlayer = false,
                networked = false,
                replaying = false,
                sandboxMode = true,
                debugEditor = true
            )
        )
        assertFalse(
            sandboxUnitDataUiAllowed(
                singlePlayer = true,
                networked = true,
                replaying = false,
                sandboxMode = true,
                debugEditor = true
            )
        )
        assertFalse(
            sandboxUnitDataUiAllowed(
                singlePlayer = true,
                networked = false,
                replaying = true,
                sandboxMode = true,
                debugEditor = true
            )
        )
        assertTrue(sandboxNativeEditorActionsAllowed(singlePlayer = false, sandboxMode = true, debugEditor = false))
        assertFalse(shouldAcceptSandboxUnitEdit(isSandboxedLocalGame = false, replaying = false))
        assertTrue(shouldAcceptSandboxUnitEdit(isSandboxedLocalGame = false, replaying = true))
    }

    @Test
    fun onlyRecognizedSystemCommandsAreInterceptedAndEditorBuildUsesTheNativeSafeRoute() {
        assertTrue(isSandboxUnitEditAction(true, SandboxUnitDataField.HP.commandId))
        assertFalse(isSandboxUnitEditAction(false, SandboxUnitDataField.HP.commandId))
        assertFalse(isSandboxUnitEditAction(true, 5))
        assertTrue(
            shouldForceEditorBuild(
                editorUnit = true,
                placementAction = true,
                sandbox = true,
                optionEnabled = true
            )
        )
        assertFalse(
            shouldForceEditorBuild(
                editorUnit = false,
                placementAction = true,
                sandbox = true,
                optionEnabled = true
            )
        )
        assertFalse(
            shouldForceEditorBuild(
                editorUnit = true,
                placementAction = true,
                sandbox = false,
                optionEnabled = true
            )
        )
        assertFalse(
            shouldForceEditorBuild(
                editorUnit = true,
                placementAction = false,
                sandbox = true,
                optionEnabled = true
            )
        )
        assertFalse(
            shouldForceEditorBuild(
                editorUnit = true,
                placementAction = true,
                sandbox = true,
                optionEnabled = false
            )
        )
    }

    @Test
    fun commandButtonsAreRebuiltWhenCapturedSelectionOrActionChanges() {
        assertTrue(mayReuseCommandButton(callbacksReplaced = false, visualsMatch = true))
        assertFalse(mayReuseCommandButton(callbacksReplaced = true, visualsMatch = true))
        assertFalse(mayReuseCommandButton(callbacksReplaced = false, visualsMatch = false))
    }

    @Test
    fun dynamicActionNamesAreResampledAtMostEveryQuarterSecond() {
        assertTrue(hudActionLabelSampleDue(Long.MIN_VALUE, 10_000L))
        assertFalse(hudActionLabelSampleDue(10_000L, 250_009_999L))
        assertTrue(hudActionLabelSampleDue(10_000L, 250_010_000L))
    }

    @Test
    fun dialogSubmitsOnlyChangedFieldsAndMaximumsPrecedeCurrentValues() {
        val initial = listOf(40f, 100f, 8f, 20f, 3f, 0.4f, 4f, 2f, 17.5f, 0f)
        val fields = sandboxUnitDataCoreFields
        val changed = sandboxChangedUnitDataEdits(
            fields,
            initial,
            listOf(75f, 150f, 8f, 20f, 3f, 0.4f, 4f, 2f, 17.5f, 0f),
        )
        assertEquals(mapOf(SandboxUnitDataField.HP to 75f, SandboxUnitDataField.MAX_HP to 150f), changed)
        assertEquals(
            listOf(
                SandboxUnitDataField.MAX_HP,
                SandboxUnitDataField.MAX_SHIELD,
                SandboxUnitDataField.MAX_ENERGY,
                SandboxUnitDataField.HP,
                SandboxUnitDataField.SHIELD,
                SandboxUnitDataField.ENERGY,
                SandboxUnitDataField.BUILD_PROGRESS,
                SandboxUnitDataField.AMMO,
                SandboxUnitDataField.KILL_COUNT,
                SandboxUnitDataField.TOTAL_DAMAGE_DEALT,
                SandboxUnitDataField.FACING,
                SandboxUnitDataField.MASS,
                SandboxUnitDataField.SHIELD_REGEN,
                SandboxUnitDataField.ARMOUR,
                SandboxUnitDataField.MAX_ATTACK_RANGE,
                SandboxUnitDataField.SHOOT_DELAY_MULTIPLIER,
                SandboxUnitDataField.SHOOT_DAMAGE_MULTIPLIER,
                SandboxUnitDataField.MOVE_SPEED,
                SandboxUnitDataField.MAX_TURN_SPEED,
                SandboxUnitDataField.FOG_OF_WAR_SIGHT_RANGE,
                SandboxUnitDataField.NANO_RANGE,
                SandboxUnitDataField.SELF_REGEN_RATE,
                SandboxUnitDataField.TARGET_HEIGHT,
                SandboxUnitDataField.NANO_FACTORY_SPEED,
            ),
            sandboxUnitDataApplyOrder,
        )
    }

    @Test
    fun customUnitNumericFieldsUseTheNativeDynamicMetadataCatalogAndSafeRanges() {
        val metadataFields = SandboxUnitDataField.entries.filter { it.dynamicMetadataFieldId != null }
        assertEquals(19, metadataFields.size)
        assertEquals(metadataFields.size, metadataFields.map { it.dynamicMetadataFieldId }.distinct().size)
        assertTrue(metadataFields.all { DynamicMetadata.getFieldById(it.dynamicMetadataFieldId!!) != null })
        assertEquals(SandboxUnitDataField.entries.size, sandboxUnitDataApplyOrder.size)
        assertTrue(SandboxUnitDataField.SHIELD_REGEN.accepts(-2f))
        assertTrue(SandboxUnitDataField.SELF_REGEN_RATE.accepts(-0.5f))
        assertFalse(SandboxUnitDataField.MAX_HP.accepts(0f))
        assertFalse(SandboxUnitDataField.FOG_OF_WAR_SIGHT_RANGE.accepts(2.5f))
        assertFalse(SandboxUnitDataField.NANO_RANGE.accepts(-2f))
        assertFalse(SandboxUnitDataField.SHOOT_DELAY_MULTIPLIER.accepts(0f))
        assertFalse(SandboxUnitDataField.ARMOUR.accepts(Float.POSITIVE_INFINITY))
        assertTrue(SandboxUnitDataField.AMMO.accepts(16_777_216f))
        assertFalse(SandboxUnitDataField.AMMO.accepts(-1f))
        assertFalse(SandboxUnitDataField.AMMO.accepts(16_777_218f))
        assertTrue(SandboxUnitDataField.FACING.accepts(-90f))
    }

    @Test
    fun customUnitDynamicMetadataEditsCopySharedDefaultsPerInstance() {
        val shared = DynamicMetadata(true).apply { moveSpeed = 1.5f }
        val edited = customUnit(id = 1L, metadata = shared)
        val untouched = customUnit(id = 2L, metadata = shared)

        applySandboxUnitData(edited, SandboxUnitDataField.MOVE_SPEED, 4f)

        assertNotSame(shared, edited.unitData)
        assertSame(shared, untouched.unitData)
        assertEquals(4f, sandboxReadUnitData(edited, SandboxUnitDataField.MOVE_SPEED))
        assertEquals(1.5f, sandboxReadUnitData(untouched, SandboxUnitDataField.MOVE_SPEED))
    }

    @Test
    fun attachedUnitsAreIndividuallySelectableAndCyclesOrInvalidObjectsAreIgnored() {
        val root = customUnit(id = 11L, metadata = DynamicMetadata(true))
        val child = customUnit(id = 12L, metadata = DynamicMetadata(true)).apply { ammo = 5 }
        val grandchild = customUnit(id = 13L, metadata = DynamicMetadata(true))
        val deadChild = customUnit(id = 14L, metadata = DynamicMetadata(true)).apply { dead = true }
        root.attachedUnits = FastArrayList().apply {
            add(child)
            add(child)
            add(null)
            add("not-a-unit")
            add(deadChild)
        }
        child.attachedUnits = FastArrayList().apply {
            add(root)
            add(grandchild)
        }

        val targets = sandboxUnitEditTargets(listOf(root))

        assertEquals(listOf("selected:11", "unit:12", "unit:13"), targets.map { it.key })
        assertEquals(listOf(root.id), targets[0].unitIds)
        assertEquals(listOf(child.id), targets[1].unitIds)
        assertEquals(listOf(grandchild.id), targets[2].unitIds)
        assertTrue(targets.all { it.dataValues.any { value -> value.field == SandboxUnitDataField.AMMO } })
        assertEquals(5f, targets[1].dataValues.single { it.field == SandboxUnitDataField.AMMO }.value)
        child.ammo = 9
        assertEquals(
            5f, targets[1].dataValues.single { it.field == SandboxUnitDataField.AMMO }.value,
            "published target data is detached from subsequent native mutations"
        )
        assertFailsWith<UnsupportedOperationException> {
            (targets[1].unitIds as MutableList<Long>).clear()
        }
    }

    @Test
    fun customOnlyValuesAreNotOfferedForVanillaUnitsAndBatchSelectionStaysExplicit() {
        val vanilla =
            unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Builder::class.java) as OrderableUnit
        val custom = customUnit(id = 22L, metadata = DynamicMetadata(true))
        val fields = sandboxUnitDataFields(listOf(vanilla, custom))
        assertTrue(SandboxUnitDataField.MOVE_SPEED in fields)
        assertEquals(null, sandboxReadUnitData(vanilla, SandboxUnitDataField.MOVE_SPEED))
        val targets = sandboxUnitEditTargets(listOf(vanilla, custom))
        assertEquals(1, targets.size)
        assertEquals(listOf(vanilla.id, custom.id), targets.single().unitIds)
    }

    @Test
    fun onlyPerUnitLocalResourcesAreExposedAndWritesStayOnTheSelectedInstance() = withNativeGameEngine {
        val local = CustomResource.getOrCreate("l_rwpp_sandbox_unit_value", false, false)
        val global = CustomResource.getOrCreate("g_rwpp_sandbox_team_value", false, true)
        val metadata = customMetadata(
            ResourceReference(false).apply {
                key = "rwpp_sandbox_unit_value"
                resource = local
            },
            ResourceReference(true).apply {
                key = "rwpp_sandbox_team_value"
                resource = global
            },
        )
        val first = customUnit(id = 31L, metadata = DynamicMetadata(true)).apply { meta = metadata }
        val second = customUnit(id = 32L, metadata = DynamicMetadata(true)).apply { meta = metadata }
        setUnitResources(first, StoredResources())
        setUnitResources(second, StoredResources())

        val fields = sandboxUnitResourceFields(listOf(first))
        assertEquals(listOf("l_rwpp_sandbox_unit_value"), fields.map { it.internalName })
        assertEquals(0.0, fields.single().initialValue)
        assertNull(sandboxLocalResourceInternalName(metadata.resourceReferences.get(1) as ResourceReference))

        assertTrue(applySandboxUnitResource(first, local, "l_rwpp_sandbox_unit_value", 12.75))
        assertEquals(0.0, fields.single().initialValue, "published resource values are immutable samples")
        assertEquals(12.75, local.getAmount(first))
        assertEquals(0.0, local.getAmount(second), "the same metadata/resource definition is shared by both units")
        assertFalse(applySandboxUnitResource(first, global, "g_rwpp_sandbox_team_value", 90.0))

        val output = OutputNetStream()
        first.getStoredResources().write(output)
        val restored = StoredResources()
        restored.read(InputNetStream(output.toByteArray()))
        assertEquals(
            12.75,
            restored.getAmount(local),
            "native StoredResources serialization stores the resource name and double"
        )
    }

    @Test
    fun resourceActionNamesAreStableAndHashCollisionsAreRejected() {
        val first = "l_Aa"
        val second = "l_BB"
        assertEquals(sandboxUnitResourceActionId(first), sandboxUnitResourceActionId(first))
        assertTrue(isSandboxUnitResourceAction(sandboxUnitResourceActionId(first)))
        assertEquals(sandboxUnitResourceActionId(first), sandboxUnitResourceActionId(second))
        assertEquals(setOf(first, second), sandboxCollidingResourceNames(listOf(first, second)))
        assertTrue(sandboxCollidingResourceNames(listOf("l_unique")).isEmpty())
    }

    @Test
    fun negativeNumericFieldsAcceptAValidSignedTextEntryWithoutAllowingLetters() {
        assertTrue(sandboxNumericInputAllowed("-0.25"))
        assertTrue(sandboxNumericInputAllowed("1.0E-12"))
        assertTrue(sandboxNumericInputAllowed("-2e+3"))
        assertTrue(sandboxNumericInputAllowed(""), "empty text remains editable while replacing a value")
        assertFalse(sandboxNumericInputAllowed("-1x"))
        assertTrue(sandboxNumericTextAccepts(SandboxUnitDataField.AMMO, "16777216"))
        assertTrue(sandboxNumericTextAccepts(SandboxUnitDataField.AMMO, "1.0E6"))
        assertFalse(
            sandboxNumericTextAccepts(SandboxUnitDataField.AMMO, "16777217"),
            "native float command payloads must not silently round integer text",
        )
        assertTrue(sandboxNumericTextAccepts(SandboxUnitDataField.FACING, "-90"))
    }

    @Test
    fun staleSandboxSnapshotsAreRejectedWhenSelectionChangesDirectly() {
        val snapshotForA = SandboxUnitDataSnapshot(
            selectionIds = listOf(101L),
            editableCount = 1,
            teams = emptyList(),
            ownerId = null,
            allianceGroup = 0,
            hp = 1f,
            maxHp = 1f,
            shield = 0f,
            maxShield = 0f,
            energy = 0f,
            buildProgress = 1f,
            editTargets = emptyList(),
        )
        assertTrue(sandboxSnapshotMatchesSelection(snapshotForA, listOf(101L)))
        assertFalse(sandboxSnapshotMatchesSelection(snapshotForA, listOf(202L)))
        assertFalse(sandboxAttachmentActionAvailable(snapshotForA))
        assertTrue(sandboxAttachmentActionAvailable(snapshotForA.copy(attachmentHostIds = listOf(101L))))
        assertEquals(
            setOf(101L),
            sandboxSelectedAttachmentHostIds(
                availableUnitIds = listOf(101L, 202L),
                selectedHostIds = listOf(101L, 303L)
            ),
        )
        assertTrue(sandboxSnapshotRequestIsCurrent(listOf(202L), 2, listOf(202L), 2))
        assertFalse(sandboxSnapshotRequestIsCurrent(listOf(101L), 1, listOf(202L), 2))
    }

    @Test
    fun resourceDoublesRoundTripThroughTheNativeRecordedCommandExactly() = withNativeGameEngine {
        val values = listOf(0.0, -0.0, 123456789.12345678, Double.MIN_VALUE, Double.MAX_VALUE)
        for (original in values) {
            val payload = sandboxSplitResourceAmount(original)
            assertNotNull(payload)
            assertTrue(
                listOf(
                    payload.commandStepRate,
                    payload.commandFloat,
                    payload.targetPointX,
                    payload.targetPointY,
                ).all { it.isFinite() && it in 0f..65535f && it % 1f == 0f })
            val restored = nativeCommandRoundTripResourceAmount(payload)
            assertEquals(original.toBits(), restored.toBits(), "native command IO must preserve $original exactly")
        }
        assertNull(sandboxSplitResourceAmount(Double.NaN))
        assertNull(sandboxSplitResourceAmount(Double.POSITIVE_INFINITY))
        assertNull(sandboxJoinResourceAmount(Float.NaN, 0f, 0f, 0f))
    }

    @Test
    fun terrainEdgeBuildingMigrationEnablesLegacyDefaultsOnceAndPreservesLaterOptOut() {
        val legacy = Settings(terrainEdgeBuilding = false)
        assertTrue(legacy.migrateTerrainEdgeBuildingPreference())
        assertTrue(legacy.terrainEdgeBuilding)
        assertEquals(1, legacy.terrainEdgeBuildingPreferenceVersion)

        legacy.terrainEdgeBuilding = false
        assertFalse(legacy.migrateTerrainEdgeBuildingPreference())
        assertFalse(legacy.terrainEdgeBuilding)

        val optedOut = Settings(terrainEdgeBuilding = false, terrainEdgeBuildingPreferenceVersion = 1)
        assertFalse(optedOut.migrateTerrainEdgeBuildingPreference())
        assertFalse(optedOut.terrainEdgeBuilding)
    }

    @Test
    fun buildProgressUsesNativeSetterPath() {
        val unit = unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Builder::class.java) as OrderableUnit
        applySandboxUnitData(unit, SandboxUnitDataField.BUILD_PROGRESS, 0.65f)
        assertEquals(0.65f, unit.buildProgress)
    }

    @Test
    fun serializedOrdinaryUnitFieldsUseSafeInstanceWritesAndNativeFacingSetter() {
        val unit = unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Builder::class.java) as OrderableUnit
        unit.ammo = 4
        unit.energy = 0x20
        unit.currentEnergy = 9f
        unit.killCount = 2
        unit.totalDamageDealt = 17.5f
        unit.turrets = arrayOf(
            unsafe.allocateInstance(com.corrodinggames.rts.game.units.UnitTurretInstance::class.java) as
                    com.corrodinggames.rts.game.units.UnitTurretInstance
        )

        applySandboxUnitData(unit, SandboxUnitDataField.AMMO, 12f)
        applySandboxUnitData(unit, SandboxUnitDataField.ENERGY, 25f)
        applySandboxUnitData(unit, SandboxUnitDataField.KILL_COUNT, 6f)
        applySandboxUnitData(unit, SandboxUnitDataField.TOTAL_DAMAGE_DEALT, 90.25f)
        applySandboxUnitData(unit, SandboxUnitDataField.FACING, -90f)

        assertEquals(12, unit.ammo)
        assertEquals(25f, unit.currentEnergy)
        assertEquals(0x20, unit.energy, "the similarly named native field is a custom flag bitmask")
        assertEquals(6, unit.killCount)
        assertEquals(90.25f, unit.totalDamageDealt)
        assertEquals(-90f, unit.angle)
        assertEquals(-90f, unit.turrets[0].angle)
        assertEquals(3, SandboxUnitDataField.FACING.commandId - SandboxUnitDataField.AMMO.commandId)
    }

    private companion object {
        val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as sun.misc.Unsafe

        fun customUnit(id: Long, metadata: DynamicMetadata): CustomUnit =
            (unsafe.allocateInstance(CustomUnit::class.java) as CustomUnit).apply {
                this.id = id
                this.unitData = metadata
                this.maxHp = 100f
                this.hp = 100f
                this.maxShield = 10f
                this.shield = 5f
            }

        fun customMetadata(vararg references: ResourceReference): CustomUnitMetadata =
            (unsafe.allocateInstance(CustomUnitMetadata::class.java) as CustomUnitMetadata).also { metadata ->
                CustomUnitMetadata::class.java.getField("resourceReferences").apply {
                    isAccessible = true
                    set(metadata, FastArrayList().apply { references.forEach { add(it) } })
                }
            }

        fun setUnitResources(unit: CustomUnit, resources: StoredResources) {
            com.corrodinggames.rts.game.units.Unit::class.java.getDeclaredField("storedResources").apply {
                isAccessible = true
                set(unit, resources)
            }
        }

        fun nativeCommandRoundTripResourceAmount(payload: SandboxResourceAmountPayload): Double {
            val teamArrayField = Team::class.java.getDeclaredField("teamArray").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val teamArray = teamArrayField.get(null) as Array<Team?>
            val previousTeam = teamArray[0]
            val testTeam = unsafe.allocateInstance(Player::class.java) as Player
            testTeam.teamId = 0
            teamArray[0] = testTeam
            try {
                val command = CommandControllerCommand(null).apply {
                    team = testTeam
                    actionId = ActionId.EMPTY
                    hasSystemAction = true
                    changeStepRate = payload.commandStepRate
                    PENDING_systemCommandFloat = payload.commandFloat
                    systemActionId = sandboxUnitResourceActionId("l_rwpp_sandbox_command_roundtrip")
                    targetPoint = PointF(payload.targetPointX, payload.targetPointY)
                }
                val output = OutputNetStream()
                command.write(output)
                val restored = CommandControllerCommand(null)
                restored.read(InputNetStream(output.toByteArray()))
                assertEquals(command.systemActionId, restored.systemActionId)
                assertEquals(payload.targetPointX, restored.targetPoint?.x)
                assertEquals(payload.targetPointY, restored.targetPoint?.y)
                return sandboxJoinResourceAmount(
                    restored.changeStepRate,
                    restored.PENDING_systemCommandFloat,
                    restored.targetPoint!!.x,
                    restored.targetPoint!!.y,
                ) ?: error("native command returned an invalid resource payload")
            } finally {
                teamArray[0] = previousTeam
            }
        }

        fun <T> withNativeGameEngine(block: () -> T): T {
            val engineField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
            val previousEngine = engineField.get(null)
            engineField.set(null, unsafe.allocateInstance(RWGameEngine::class.java))
            try {
                return block()
            } finally {
                engineField.set(null, previousEngine)
            }
        }
    }
}
