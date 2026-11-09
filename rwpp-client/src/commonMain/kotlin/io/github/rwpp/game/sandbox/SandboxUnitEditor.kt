/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.sandbox

import android.graphics.PointF
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.EditorUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadata
import com.corrodinggames.rts.game.units.custom.DynamicMetadata
import com.corrodinggames.rts.game.units.custom.components.UnitAttachmentsAttachmentSlot
import com.corrodinggames.rts.game.units.custom.resources.CustomResource
import com.corrodinggames.rts.game.units.custom.resources.ResourceReference
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import com.corrodinggames.rts.gameFramework.utility.UnitList
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.Game
import io.github.rwpp.appKoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import com.corrodinggames.rts.game.units.Unit as GameUnit

internal data class SandboxTeamOption(val id: Int, val name: String)

internal data class SandboxUnitDataSnapshot(
    val selectionIds: List<Long>,
    val editableCount: Int,
    val teams: List<SandboxTeamOption>,
    val ownerId: Int?,
    val allianceGroup: Int,
    val hp: Float,
    val maxHp: Float,
    val shield: Float,
    val maxShield: Float,
    val energy: Float,
    val buildProgress: Float,
    val editTargets: List<SandboxUnitEditTarget>,
    /** Selected custom units that have at least one native attachment slot. */
    val attachmentHostIds: List<Long> = emptyList(),
)

internal fun sandboxAttachmentActionAvailable(snapshot: SandboxUnitDataSnapshot): Boolean =
    snapshot.attachmentHostIds.isNotEmpty()

internal fun sandboxSelectedAttachmentHostIds(
    availableUnitIds: Collection<Long>,
    selectedHostIds: Collection<Long>,
): Set<Long> = availableUnitIds.toSet().intersect(selectedHostIds.toSet())

internal data class SandboxUnitEditTarget(
    val key: String,
    val labelKey: String,
    val labelUnitId: Long? = null,
    val labelUnitName: String? = null,
    val unitIds: List<Long>,
    val dataValues: List<SandboxUnitFieldValue>,
    val resources: List<SandboxUnitResourceField>,
)

internal data class SandboxUnitFieldValue(
    val field: SandboxUnitDataField,
    val value: Float,
)

internal data class SandboxUnitResourceField(
    val internalName: String,
    val label: String,
    val initialValue: Double,
)

internal data class SandboxUnitResourceEdit(val internalName: String, val value: Double)

internal data class SandboxAttachmentSlotOption(
    val index: Int,
    val name: String,
    val occupiedUnitId: Long?,
    val occupiedUnitName: String?,
)

internal data class SandboxAttachmentHostOption(
    val id: Long,
    val label: String,
    val slots: List<SandboxAttachmentSlotOption>,
)

internal data class SandboxAttachmentUnitOption(
    val id: Long,
    val label: String,
    val typeName: String,
)

internal data class SandboxAttachmentTypeOption(val internalName: String, val label: String)

internal data class SandboxAttachmentOptionsSnapshot(
    val hosts: List<SandboxAttachmentHostOption>,
    val unattachedUnits: List<SandboxAttachmentUnitOption>,
    val attachedUnits: List<SandboxAttachmentUnitOption>,
    val unitTypes: List<SandboxAttachmentTypeOption>,
)

internal enum class SandboxAttachmentRejection {
    NONE,
    DEAD_UNIT,
    SAME_UNIT,
    CHILD_ALREADY_ATTACHED,
    CHILD_IS_TRANSPORTED,
    CYCLE,
    CORRUPT_PARENT_CHAIN,
}

internal data class SandboxResourceAmountPayload(
    val commandStepRate: Float,
    val commandFloat: Float,
    val targetPointX: Float,
    val targetPointY: Float,
)

private fun <T> immutableSnapshotList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** The custom field editor is deliberately limited to a local sandbox. */
internal fun sandboxUnitDataUiAllowed(engine: GameEngine): Boolean =
    sandboxUnitDataUiAllowed(
        singlePlayer = engine.isSinglePlayer,
        networked = engine.networkEngine.networked,
        replaying = engine.replayEngine.isPlayingReplay,
        sandboxMode = engine.networkEngine.sandboxMode,
        debugEditor = engine.debugEditorMode,
    )

internal fun sandboxUnitDataUiAllowed(
    singlePlayer: Boolean,
    networked: Boolean,
    replaying: Boolean,
    sandboxMode: Boolean,
    debugEditor: Boolean,
): Boolean = singlePlayer && !networked && !replaying && (sandboxMode || debugEditor)

/** Native EditorUnit actions remain available in network sandboxes through the game's own command queue. */
internal fun sandboxNativeEditorActionsAllowed(engine: GameEngine): Boolean =
    sandboxNativeEditorActionsAllowed(
        singlePlayer = engine.isSinglePlayer,
        sandboxMode = engine.networkEngine.sandboxMode,
        debugEditor = engine.debugEditorMode,
    )

internal fun sandboxNativeEditorActionsAllowed(
    singlePlayer: Boolean,
    sandboxMode: Boolean,
    debugEditor: Boolean,
): Boolean = sandboxMode || (singlePlayer && debugEditor)

private fun sandboxUnitDataSnapshot(engine: GameEngine, units: List<GameUnit>): SandboxUnitDataSnapshot? {
    if (!sandboxUnitDataUiAllowed(engine)) return null
    val editable = units.asSequence().filterIsInstance<OrderableUnit>()
        .filterNot { it is EditorUnit }
        .filterNot { it.dead }
        .toList()
    if (editable.isEmpty()) return null
    val first = editable.first()
    val editTargets = sandboxUnitEditTargets(editable)
    val teams = Team.getTeams().filterIsInstance<Team>().mapNotNull { team ->
        if (team.teamId < 0) null else SandboxTeamOption(team.teamId, team.name ?: "Team ${team.teamId + 1}")
    }
    val owner = first.team.takeIf { editable.all { unit -> unit.team === first.team } }?.teamId
    val attachmentHostIds = editable.asSequence().filterIsInstance<CustomUnit>()
        .filter { sandboxAttachmentSlotOptions(it).isNotEmpty() }
        .map { it.id }
        .toList()
    return SandboxUnitDataSnapshot(
        selectionIds = immutableSnapshotList(editable.map { it.id }),
        editableCount = editable.size,
        teams = immutableSnapshotList(teams),
        ownerId = owner,
        allianceGroup = first.team.allyTeam.coerceIn(0, 4),
        hp = first.hp,
        maxHp = first.maxHp,
        shield = first.shield,
        maxShield = first.maxShield,
        energy = first.currentEnergy,
        buildProgress = first.buildProgress,
        editTargets = immutableSnapshotList(editTargets),
        attachmentHostIds = immutableSnapshotList(attachmentHostIds),
    )
}

/** Resolve and sample all native unit state on the serialized game dispatcher. */
internal fun requestSandboxUnitDataSnapshot(
    selectionIds: List<Long>,
    callbackDispatcher: CoroutineDispatcher = GameMainDispatcher,
    onSnapshot: (SandboxUnitDataSnapshot?) -> Unit,
) {
    val stableIds = selectionIds.distinct().toList()
    GameScope.launch(GameMainDispatcher) {
        val snapshot = runCatching {
            val engine = GameEngine.getInstance()
            val units = resolveSandboxEditableUnits(stableIds)
            sandboxUnitDataSnapshot(engine, units)
        }.getOrNull()
        if (callbackDispatcher == GameMainDispatcher) onSnapshot(snapshot)
        else withContext(callbackDispatcher) { onSnapshot(snapshot) }
    }
}

/** Unit IDs are resolved only on GameMainDispatcher, then revalidated before command creation. */
private fun resolveSandboxEditableUnits(unitIds: List<Long>): List<OrderableUnit> =
    unitIds.distinct().mapNotNull { id ->
        runCatching { GameObject.getOrderableUnitFromId(id, true) }
            .getOrNull()?.takeIf { it.id == id && !it.dead && it !is EditorUnit }
    }.toList()

private const val SANDBOX_ATTACH_EXISTING_ACTION = 827300
private const val SANDBOX_ATTACH_SPAWN_ACTION = 827301
private const val SANDBOX_DETACH_ACTION = 827302
private const val SANDBOX_DETACH_SLOT_ACTION = 827303
private const val SANDBOX_ATTACHMENT_ACTION_LIMIT = SANDBOX_DETACH_SLOT_ACTION + 1

private fun sandboxNativeListSnapshot(list: FastArrayList?): List<Any?> {
    list ?: return emptyList()
    return runCatching {
        val items = list.items()
        val count = list.size.coerceIn(0, items.size)
        List(count) { items[it] }
    }.getOrDefault(emptyList())
}

/** Snapshots the native UnitList through its backing array, without its fail-fast iterator. */
private fun sandboxNativeUnitListSnapshot(list: UnitList?): List<Any?> {
    list ?: return emptyList()
    return runCatching {
        val items = list.items()
        val count = list.size.coerceIn(0, items.size)
        List(count) { items[it] }
    }.getOrDefault(emptyList())
}

private fun sandboxUnitLabel(unit: OrderableUnit): String {
    val name = runCatching { unit.unitTypeInterface?.displayName }.getOrNull()
        ?.takeIf(String::isNotBlank) ?: unit.javaClass.simpleName.ifBlank { "Unit" }
    return "$name #${unit.id}"
}

private fun sandboxAttachmentSlotOptions(host: CustomUnit): List<SandboxAttachmentSlotOption> {
    val definitions = sandboxNativeListSnapshot(host.meta?.attachmentSlots)
    return immutableSnapshotList(definitions.mapNotNull { item ->
        val slot = item as? UnitAttachmentsAttachmentSlot ?: return@mapNotNull null
        val index = runCatching { slot.slotIndex.toInt() }.getOrNull() ?: return@mapNotNull null
        if (index < 0 || index > Short.MAX_VALUE) return@mapNotNull null
        val occupant = runCatching { host.getUnitInAttachmentSlot(slot) }.getOrNull()
        val occupantLabel = occupant?.let(::sandboxUnitLabel)
            ?: occupant?.let { "Unit #${it.id}" }
        SandboxAttachmentSlotOption(
            index = index,
            name = runCatching { slot.name }.getOrNull()?.takeIf(String::isNotBlank) ?: "Slot ${index + 1}",
            occupiedUnitId = occupant?.id,
            occupiedUnitName = occupant?.let { unit ->
                occupantLabel?.let { if (unit.dead) "$it (dead)" else it }
            },
        )
    })
}

/** Snapshot all native attachment choices on the serialized game dispatcher. */
internal fun requestSandboxAttachmentOptions(
    selectedHostIds: List<Long>,
    callbackDispatcher: CoroutineDispatcher = GameMainDispatcher,
    onSnapshot: (SandboxAttachmentOptionsSnapshot?) -> Unit,
) {
    val requestedHostIds = selectedHostIds.distinct().toSet()
    if (requestedHostIds.isEmpty()) {
        GameScope.launch(GameMainDispatcher) {
            if (callbackDispatcher == GameMainDispatcher) onSnapshot(null)
            else withContext(callbackDispatcher) { onSnapshot(null) }
        }
        return
    }
    GameScope.launch(GameMainDispatcher) {
        val snapshot = runCatching {
            val engine = GameEngine.getInstance()
            if (!sandboxUnitDataUiAllowed(engine)) return@runCatching null
            val units = sandboxNativeUnitListSnapshot(GameUnit.unitList)
                .filterIsInstance<GameUnit>()
                .filterIsInstance<OrderableUnit>()
                .filterNot { it.dead || it is EditorUnit }
                .distinctBy { it.id }
            val eligibleHostIds = sandboxSelectedAttachmentHostIds(units.map { it.id }, requestedHostIds)
            val hosts = units.asSequence().filter { it.id in eligibleHostIds }
                .filterIsInstance<CustomUnit>().mapNotNull { host ->
                    val slots = sandboxAttachmentSlotOptions(host)
                    if (slots.isEmpty()) null else SandboxAttachmentHostOption(
                        id = host.id,
                        label = sandboxUnitLabel(host),
                        slots = slots,
                    )
                }.toList()
            val existing = units.map { unit ->
                SandboxAttachmentUnitOption(
                    id = unit.id,
                    label = sandboxUnitLabel(unit),
                    typeName = runCatching { unit.unitTypeInterface?.internalName }.getOrNull().orEmpty(),
                )
            }
            val unitsById = units.associateBy { it.id }
            val types = sandboxLoadedUnitTypes()
                .mapNotNull { type ->
                    runCatching {
                        val internalName = type.internalName
                        if (internalName.isBlank()) null
                        else SandboxAttachmentTypeOption(internalName, type.displayName.ifBlank { internalName })
                    }.getOrNull()
                }
                .distinctBy { it.internalName }
                .sortedWith(compareBy<SandboxAttachmentTypeOption> { it.label.lowercase() }.thenBy { it.internalName })
            SandboxAttachmentOptionsSnapshot(
                hosts = immutableSnapshotList(hosts),
                unattachedUnits = immutableSnapshotList(existing.filter { option ->
                    unitsById[option.id]?.attachedToUnit == null && unitsById[option.id]?.transportedBy == null
                }),
                attachedUnits = immutableSnapshotList(existing.filter { option ->
                    unitsById[option.id]?.attachedToUnit != null
                }),
                unitTypes = immutableSnapshotList(types),
            )
        }.getOrNull()
        if (callbackDispatcher == GameMainDispatcher) onSnapshot(snapshot)
        else withContext(callbackDispatcher) { onSnapshot(snapshot) }
    }
}

internal fun sandboxAttachmentRejection(
    host: OrderableUnit,
    child: OrderableUnit,
): SandboxAttachmentRejection {
    if (host.dead || child.dead) return SandboxAttachmentRejection.DEAD_UNIT
    if (host === child) return SandboxAttachmentRejection.SAME_UNIT
    if (child.attachedToUnit != null) return SandboxAttachmentRejection.CHILD_ALREADY_ATTACHED
    if (child.transportedBy != null) return SandboxAttachmentRejection.CHILD_IS_TRANSPORTED

    val seen = Collections.newSetFromMap(IdentityHashMap<OrderableUnit, Boolean>())
    var cursor: OrderableUnit? = host
    while (cursor != null) {
        if (cursor === child) return SandboxAttachmentRejection.CYCLE
        if (!seen.add(cursor)) return SandboxAttachmentRejection.CORRUPT_PARENT_CHAIN
        cursor = cursor.attachedToUnit
    }
    return SandboxAttachmentRejection.NONE
}

internal fun sandboxAttachmentSlotPayload(index: Int): Float? =
    index.takeIf { it in 0..Short.MAX_VALUE }?.toFloat()

internal fun sandboxAttachmentSlotFromPayload(value: Float): Int? =
    value.takeIf { it.isFinite() && it >= 0f && it <= Short.MAX_VALUE.toFloat() && it % 1f == 0f }
        ?.toInt()

private const val SANDBOX_OWNER_ACTION_BASE = 827000
private const val SANDBOX_ALLIANCE_ACTION_BASE = 827100
private const val SANDBOX_DATA_ACTION_BASE = 827200
private const val SANDBOX_OWNER_ACTION_LIMIT = SANDBOX_OWNER_ACTION_BASE + 100
private const val SANDBOX_ALLIANCE_ACTION_LIMIT = SANDBOX_ALLIANCE_ACTION_BASE + 5
private const val SANDBOX_DATA_ACTION_LIMIT = SANDBOX_DATA_ACTION_BASE + 24
private const val SANDBOX_RESOURCE_ACTION_MARKER = 0xC0000000.toInt()
private const val SANDBOX_RESOURCE_ACTION_MARKER_MASK = 0xC0000000.toInt()
private const val SANDBOX_RESOURCE_ACTION_HASH_MASK = 0x3FFFFFFF
private const val MAX_SANDBOX_EDIT_VALUE = 1_000_000_000f
private const val MAX_ATTACHED_UNIT_EDIT_TARGETS = 128

/**
 * CustomUnit DynamicMetadata values use native per-instance copy-on-write. Ordinary unit values
 * are limited to fields with native instance writes/setters and stable Unit.write/read state.
 * The native `energy` integer is a mod flag bitmask, so the editor exposes only currentEnergy.
 */
internal enum class SandboxUnitDataField(
    private val commandOffset: Int,
    val labelKey: String,
    val dynamicMetadataFieldId: Int?,
    val minimum: Float,
    val maximum: Float,
    val integral: Boolean = false,
    val customOnly: Boolean = false,
) {
    HP(0, "rwpp.sandbox.editor.hp", 4, 0f, MAX_SANDBOX_EDIT_VALUE),
    MAX_HP(1, "rwpp.sandbox.editor.maxHp", 3, 1f, MAX_SANDBOX_EDIT_VALUE, integral = true),
    SHIELD(2, "rwpp.sandbox.editor.shield", 6, 0f, MAX_SANDBOX_EDIT_VALUE),
    MAX_SHIELD(3, "rwpp.sandbox.editor.maxShield", 5, 0f, MAX_SANDBOX_EDIT_VALUE, integral = true),
    ENERGY(4, "rwpp.sandbox.editor.energy", 2, 0f, MAX_SANDBOX_EDIT_VALUE),
    BUILD_PROGRESS(5, "rwpp.sandbox.editor.buildProgress", null, 0f, 1f),
    MASS(6, "rwpp.sandbox.editor.mass", 0, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    MAX_ENERGY(7, "rwpp.sandbox.editor.maxEnergy", 1, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    SHIELD_REGEN(
        8,
        "rwpp.sandbox.editor.shieldRegen",
        7,
        -MAX_SANDBOX_EDIT_VALUE,
        MAX_SANDBOX_EDIT_VALUE,
        customOnly = true
    ),
    ARMOUR(9, "rwpp.sandbox.editor.armour", 8, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    MAX_ATTACK_RANGE(10, "rwpp.sandbox.editor.maxAttackRange", 9, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    SHOOT_DELAY_MULTIPLIER(
        11,
        "rwpp.sandbox.editor.shootDelayMultiplier",
        10,
        0.0001f,
        MAX_SANDBOX_EDIT_VALUE,
        customOnly = true
    ),
    SHOOT_DAMAGE_MULTIPLIER(
        12,
        "rwpp.sandbox.editor.shootDamageMultiplier",
        11,
        0f,
        MAX_SANDBOX_EDIT_VALUE,
        customOnly = true
    ),
    MOVE_SPEED(13, "rwpp.sandbox.editor.moveSpeed", 12, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    MAX_TURN_SPEED(14, "rwpp.sandbox.editor.maxTurnSpeed", 13, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    FOG_OF_WAR_SIGHT_RANGE(
        15,
        "rwpp.sandbox.editor.fogOfWarSightRange",
        14,
        0f,
        MAX_SANDBOX_EDIT_VALUE,
        integral = true,
        customOnly = true
    ),
    NANO_RANGE(
        16,
        "rwpp.sandbox.editor.nanoRange",
        15,
        -1f,
        MAX_SANDBOX_EDIT_VALUE,
        integral = true,
        customOnly = true
    ),
    SELF_REGEN_RATE(
        17,
        "rwpp.sandbox.editor.selfRegenRate",
        16,
        -MAX_SANDBOX_EDIT_VALUE,
        MAX_SANDBOX_EDIT_VALUE,
        customOnly = true
    ),
    TARGET_HEIGHT(
        18,
        "rwpp.sandbox.editor.targetHeight",
        17,
        -MAX_SANDBOX_EDIT_VALUE,
        MAX_SANDBOX_EDIT_VALUE,
        customOnly = true
    ),
    NANO_FACTORY_SPEED(19, "rwpp.sandbox.editor.nanoFactorySpeed", 18, 0f, MAX_SANDBOX_EDIT_VALUE, customOnly = true),
    AMMO(20, "rwpp.sandbox.editor.ammo", null, 0f, 16_777_216f, integral = true),
    KILL_COUNT(21, "rwpp.sandbox.editor.killCount", null, 0f, 16_777_216f, integral = true),
    TOTAL_DAMAGE_DEALT(
        22,
        "rwpp.sandbox.editor.totalDamageDealt",
        null,
        -MAX_SANDBOX_EDIT_VALUE,
        MAX_SANDBOX_EDIT_VALUE
    ),
    FACING(23, "rwpp.sandbox.editor.facing", null, -360f, 360f);

    val commandId: Int get() = SANDBOX_DATA_ACTION_BASE + commandOffset

    fun accepts(value: Float): Boolean = value.isFinite() && value in minimum..maximum &&
            (!integral || value % 1f == 0f)
}

internal val sandboxUnitDataApplyOrder = listOf(
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
)

internal val sandboxUnitDataCoreFields = listOf(
    SandboxUnitDataField.HP,
    SandboxUnitDataField.MAX_HP,
    SandboxUnitDataField.SHIELD,
    SandboxUnitDataField.MAX_SHIELD,
    SandboxUnitDataField.ENERGY,
    SandboxUnitDataField.BUILD_PROGRESS,
    SandboxUnitDataField.AMMO,
    SandboxUnitDataField.KILL_COUNT,
    SandboxUnitDataField.TOTAL_DAMAGE_DEALT,
    SandboxUnitDataField.FACING,
)

internal fun sandboxChangedUnitDataEdits(
    fields: List<SandboxUnitDataField>,
    initial: List<Float>,
    current: List<Float?>,
): Map<SandboxUnitDataField, Float> = buildMap {
    fields.forEachIndexed { index, field ->
        val before = initial.getOrNull(index)
        val after = current.getOrNull(index)
        if (before != null && after != null && field.accepts(after) && before != after) put(field, after)
    }
}

internal fun sandboxSnapshotMatchesSelection(
    snapshot: SandboxUnitDataSnapshot?,
    selectionIds: List<Long>,
): Boolean = snapshot?.selectionIds == selectionIds

internal fun sandboxSnapshotRequestIsCurrent(
    requestedIds: List<Long>,
    requestedGeneration: Int,
    currentIds: List<Long>,
    currentGeneration: Int,
): Boolean = requestedGeneration == currentGeneration && requestedIds == currentIds

private data class ResolvedSandboxUnitEditTarget(
    val key: String,
    val labelKey: String,
    val labelUnitId: Long? = null,
    val labelUnitName: String? = null,
    val units: List<OrderableUnit>,
)

internal fun sandboxUnitEditTargets(units: List<GameUnit>): List<SandboxUnitEditTarget> {
    val snapshots = collectSandboxUnitEditTargets(units).map { target ->
        val dataValues = immutableSnapshotList(sandboxUnitDataFields(target.units).mapNotNull { field ->
            val source = if (field.customOnly) target.units.filterIsInstance<CustomUnit>().firstOrNull()
            else target.units.firstOrNull()
            source?.let { sandboxReadUnitData(it, field) }?.let { value -> SandboxUnitFieldValue(field, value) }
        })
        SandboxUnitEditTarget(
            key = target.key,
            labelKey = target.labelKey,
            labelUnitId = target.labelUnitId,
            labelUnitName = target.labelUnitName,
            unitIds = immutableSnapshotList(target.units.map { it.id }),
            dataValues = dataValues,
            resources = immutableSnapshotList(sandboxUnitResourceFields(target.units)),
        )
    }
    return immutableSnapshotList(snapshots)
}

private fun collectSandboxUnitEditTargets(units: List<GameUnit>): List<ResolvedSandboxUnitEditTarget> {
    val selected = units.asSequence().filterIsInstance<OrderableUnit>()
        .filterNot { it is EditorUnit }
        .filterNot { it.dead }
        .distinctBy { it.id }
        .toList()
    if (selected.isEmpty()) return emptyList()

    val targets = mutableListOf(
        ResolvedSandboxUnitEditTarget(
            key = "selected:${selected.joinToString(",") { it.id.toString() }}",
            labelKey = "rwpp.sandbox.editor.selectedUnits",
            units = selected,
        )
    )
    val seenIds = HashSet<Long>().apply { selected.forEach { add(it.id) } }
    val customParents = selected.filterIsInstance<CustomUnit>().toMutableList()
    var parentIndex = 0
    while (parentIndex < customParents.size && targets.size <= MAX_ATTACHED_UNIT_EDIT_TARGETS) {
        val parent = customParents[parentIndex++]
        val attached = parent.attachedUnits ?: continue
        val attachedSnapshot = runCatching {
            val backingItems = attached.items()
            val count = attached.size.coerceIn(0, backingItems.size)
            List(count) { backingItems[it] }
        }.getOrNull() ?: continue
        for (candidate in attachedSnapshot) {
            val child = candidate as? OrderableUnit ?: continue
            if (child === parent || child.dead || !seenIds.add(child.id)) continue
            targets += ResolvedSandboxUnitEditTarget(
                key = "unit:${child.id}",
                labelKey = "rwpp.sandbox.editor.attachedUnit",
                labelUnitId = child.id,
                labelUnitName = runCatching { child.unitTypeInterface?.displayName }
                    .getOrNull()?.takeIf(String::isNotBlank),
                units = listOf(child),
            )
            if (child is CustomUnit) customParents += child
            if (targets.size > MAX_ATTACHED_UNIT_EDIT_TARGETS) break
        }
    }
    return targets
}

/** Local custom resources are identified by the native `l_<key>` name used in StoredResources. */
internal fun sandboxLocalResourceInternalName(reference: ResourceReference): String? {
    if (reference.isGlobal) return null
    val resource = reference.resource ?: return null
    if (resource.isGlobal || resource.isBuiltIn) return null
    val key = reference.key?.takeIf(String::isNotEmpty) ?: return null
    val internalName = "l_$key"
    return internalName.takeIf { CustomResource.getByName(it) === resource }
}

/** Java String.hashCode is stable across JVM runs; collisions are rejected before offering writes. */
internal fun sandboxUnitResourceActionId(internalName: String): Int =
    SANDBOX_RESOURCE_ACTION_MARKER or (internalName.hashCode() and SANDBOX_RESOURCE_ACTION_HASH_MASK)

internal fun isSandboxUnitResourceAction(actionId: Int): Boolean =
    (actionId and SANDBOX_RESOURCE_ACTION_MARKER_MASK) == SANDBOX_RESOURCE_ACTION_MARKER

internal fun sandboxCollidingResourceNames(internalNames: Iterable<String>): Set<String> =
    internalNames.distinct().groupBy(::sandboxUnitResourceActionId)
        .values.filter { it.size > 1 }.flatten().toSet()

/** Split a native double into four exactly representable 16-bit integer float words. */
internal fun sandboxSplitResourceAmount(value: Double): SandboxResourceAmountPayload? {
    if (!value.isFinite()) return null
    val bits = value.toBits()
    fun word(shift: Int) = ((bits ushr shift) and 0xFFFFL).toFloat()
    return SandboxResourceAmountPayload(
        commandStepRate = word(48),
        commandFloat = word(32),
        targetPointX = word(16),
        targetPointY = word(0),
    )
}

internal fun sandboxJoinResourceAmount(
    commandStepRate: Float,
    commandFloat: Float,
    targetPointX: Float,
    targetPointY: Float,
): Double? {
    val words = floatArrayOf(commandStepRate, commandFloat, targetPointX, targetPointY)
    if (words.any { !it.isFinite() || it < 0f || it > 65535f || it % 1f != 0f }) return null
    val bits = words.fold(0L) { result, word -> (result shl 16) or word.toLong() }
    return Double.fromBits(bits).takeIf(Double::isFinite)
}

internal fun sandboxUnitResourceFields(units: List<GameUnit>): List<SandboxUnitResourceField> {
    val referencesByName = linkedMapOf<String, MutableList<Pair<CustomUnit, CustomResource>>>()
    for (unit in units.filterIsInstance<CustomUnit>()) {
        for ((internalName, resource) in sandboxUnitLocalResources(unit)) {
            val entries = referencesByName.getOrPut(internalName) { mutableListOf() }
            if (entries.none { it.first.id == unit.id }) entries += unit to resource
        }
    }
    if (referencesByName.isEmpty()) return emptyList()

    val knownNames = sandboxKnownLocalResourceNames(units) + referencesByName.keys
    val collisions = sandboxCollidingResourceNames(knownNames)
    return referencesByName.mapNotNull { (internalName, references) ->
        if (internalName in collisions) return@mapNotNull null
        val (unit, resource) = references.firstOrNull() ?: return@mapNotNull null
        val amount = runCatching { resource.getAmount(unit) }.getOrNull() ?: return@mapNotNull null
        if (!amount.isFinite()) return@mapNotNull null
        val displayName = runCatching { resource.displayName }.getOrNull()?.takeIf(String::isNotBlank)
            ?: internalName
        SandboxUnitResourceField(
            internalName = internalName,
            label = "$displayName ($internalName)",
            initialValue = amount,
        )
    }
}

internal fun sandboxChangedUnitResourceEdits(
    fields: List<SandboxUnitResourceField>,
    current: List<Double?>,
): List<SandboxUnitResourceEdit> = buildList {
    fields.forEachIndexed { index, field ->
        val value = current.getOrNull(index) ?: return@forEachIndexed
        if (value != field.initialValue && sandboxSplitResourceAmount(value) != null) {
            add(SandboxUnitResourceEdit(field.internalName, value))
        }
    }
}

/** Discovers local resources from loaded metadata plus the edited custom units. */
private fun sandboxKnownLocalResourceNames(units: List<GameUnit>): Set<String> = buildSet {
    val metadata = mutableListOf<CustomUnitMetadata>()
    runCatching {
        val active = CustomUnitMetadata.activeCustomUnits
        val backing = active.toArray()
        val count = active.size.coerceIn(0, backing.size)
        for (index in 0 until count) (backing[index] as? CustomUnitMetadata)?.let(metadata::add)
    }
    units.filterIsInstance<CustomUnit>().forEach { unit -> unit.meta?.let(metadata::add) }
    metadata.forEach { customMetadata ->
        sandboxMetadataLocalResources(customMetadata).forEach { (name, _) -> add(name) }
    }
}

private fun sandboxUnitLocalResources(unit: CustomUnit): List<Pair<String, CustomResource>> {
    val metadata = runCatching { unit.meta }.getOrNull() ?: return emptyList()
    return sandboxMetadataLocalResources(metadata)
}

private fun sandboxMetadataLocalResources(metadata: CustomUnitMetadata): List<Pair<String, CustomResource>> {
    val references = runCatching {
        val list = metadata.resourceReferences
        val backing = list.items()
        val count = list.size.coerceIn(0, backing.size)
        List(count) { backing[it] as? ResourceReference }
    }.getOrNull() ?: return emptyList()
    return references.mapNotNull { reference ->
        reference ?: return@mapNotNull null
        val name = sandboxLocalResourceInternalName(reference) ?: return@mapNotNull null
        val resource = reference.resource ?: return@mapNotNull null
        name to resource
    }
}

private fun sandboxResourceNameForAction(actionId: Int, units: List<OrderableUnit>): String? {
    if (!isSandboxUnitResourceAction(actionId)) return null
    val matches = sandboxKnownLocalResourceNames(units)
        .filter { sandboxUnitResourceActionId(it) == actionId }
        .distinct()
    return matches.singleOrNull()
}

internal fun sandboxCanApplyUnitResource(unit: CustomUnit, internalName: String): Boolean =
    sandboxUnitLocalResources(unit).any { (name, resource) ->
        name == internalName && !resource.isGlobal && !resource.isBuiltIn
    }

internal fun applySandboxUnitResource(
    unit: CustomUnit,
    resource: CustomResource,
    internalName: String,
    value: Double,
): Boolean {
    if (!value.isFinite() || sandboxSplitResourceAmount(value) == null || resource.isGlobal || resource.isBuiltIn) {
        return false
    }
    if (!sandboxUnitLocalResources(unit).any { (name, candidate) ->
            name == internalName && candidate === resource
        }
    ) return false
    return runCatching {
        resource.setAmount(unit, value)
        true
    }.getOrDefault(false)
}

internal fun sandboxUnitDataFields(units: List<GameUnit>): List<SandboxUnitDataField> {
    val hasCustomUnit = units.any { it is CustomUnit }
    return sandboxUnitDataApplyOrder.filter { !it.customOnly || hasCustomUnit }
}

internal fun sandboxReadUnitData(unit: OrderableUnit, field: SandboxUnitDataField): Float? {
    if (field.customOnly && unit !is CustomUnit) return null
    if (unit is CustomUnit) {
        val dynamicId = field.dynamicMetadataFieldId
        if (dynamicId != null) {
            val dynamicField = DynamicMetadata.getFieldById(dynamicId) ?: return null
            return dynamicField.getValue(unit, unit.unitData).toFloat()
        }
    }
    return when (field) {
        SandboxUnitDataField.HP -> unit.hp
        SandboxUnitDataField.MAX_HP -> unit.maxHp
        SandboxUnitDataField.SHIELD -> unit.shield
        SandboxUnitDataField.MAX_SHIELD -> unit.maxShield
        SandboxUnitDataField.ENERGY -> unit.currentEnergy
        SandboxUnitDataField.BUILD_PROGRESS -> unit.buildProgress
        SandboxUnitDataField.AMMO -> unit.ammo.toFloat()
        SandboxUnitDataField.KILL_COUNT -> unit.killCount.toFloat()
        SandboxUnitDataField.TOTAL_DAMAGE_DEALT -> unit.totalDamageDealt
        SandboxUnitDataField.FACING -> unit.angle
        else -> null
    }
}

internal fun isSandboxEditorAction(actionId: Int): Boolean =
    actionId in SANDBOX_OWNER_ACTION_BASE until SANDBOX_OWNER_ACTION_LIMIT ||
            actionId in SANDBOX_ALLIANCE_ACTION_BASE until SANDBOX_ALLIANCE_ACTION_LIMIT ||
            actionId in SANDBOX_DATA_ACTION_BASE until SANDBOX_DATA_ACTION_LIMIT ||
            actionId in SANDBOX_ATTACH_EXISTING_ACTION until SANDBOX_ATTACHMENT_ACTION_LIMIT ||
            isSandboxUnitResourceAction(actionId)

internal fun submitSandboxAttachExisting(hostId: Long, slotIndex: Int, childId: Long) {
    val slotPayload = sandboxAttachmentSlotPayload(slotIndex) ?: return
    GameScope.launch(GameMainDispatcher) {
        val engine = GameEngine.getInstance()
        if (!sandboxUnitDataUiAllowed(engine)) return@launch
        val host = resolveSandboxEditableUnits(listOf(hostId)).singleOrNull() as? CustomUnit ?: return@launch
        val child = resolveSandboxEditableUnits(listOf(childId)).singleOrNull() ?: return@launch
        if (sandboxAttachmentRejection(host, child) != SandboxAttachmentRejection.NONE) return@launch
        if (sandboxEmptyAttachmentSlot(host, slotIndex) == null) return@launch

        val command = (engine.interfaceEngine as NativeSandboxEditorCommandAccess).rwppCreatePlayerCommand()
        val payload = command as NativeSandboxEditorPayloadAccess
        payload.rwppCommandUnits().clear()
        payload.rwppPendingUnitIds().clear()
        command.addUnit(child)
        command.targetUnit = host
        command.hasSystemAction = true
        command.systemActionId = SANDBOX_ATTACH_EXISTING_ACTION
        command.changeStepRate = 0f
        command.PENDING_systemCommandFloat = slotPayload
    }
}

internal fun submitSandboxAttachUnitType(hostId: Long, slotIndex: Int, unitTypeName: String) {
    val typeName = unitTypeName.takeIf(String::isNotBlank) ?: return
    val slotPayload = sandboxAttachmentSlotPayload(slotIndex) ?: return
    GameScope.launch(GameMainDispatcher) {
        val engine = GameEngine.getInstance()
        if (!sandboxUnitDataUiAllowed(engine)) return@launch
        val host = resolveSandboxEditableUnits(listOf(hostId)).singleOrNull() as? CustomUnit ?: return@launch
        if (sandboxEmptyAttachmentSlot(host, slotIndex) == null) return@launch
        val type = sandboxLoadedUnitTypes().singleOrNull {
            runCatching { it.internalName == typeName }.getOrDefault(false)
        } ?: return@launch

        val command = (engine.interfaceEngine as NativeSandboxEditorCommandAccess).rwppCreatePlayerCommand()
        val payload = command as NativeSandboxEditorPayloadAccess
        payload.rwppCommandUnits().clear()
        payload.rwppPendingUnitIds().clear()
        command.targetUnit = host
        command.setBuildWaypoint(host.x, host.y, type, 0)
        command.hasSystemAction = true
        command.systemActionId = SANDBOX_ATTACH_SPAWN_ACTION
        command.changeStepRate = 0f
        command.PENDING_systemCommandFloat = slotPayload
    }
}

/** Includes active mod-defined UnitTypes as well as the built-in UnitType array. */
private fun sandboxLoadedUnitTypes(): List<UnitTypeInterface> {
    val types = ArrayList<UnitTypeInterface>()
    runCatching { appKoin.get<Game>().getAllUnitTypes().toList() }
        .getOrDefault(emptyList())
        .forEach(types::add)

    // activeCustomUnits can be replaced while mods reload. Read a detached reference array and
    // discard null or non-metadata entries before any fields are inspected.
    val activeCustomSnapshot = runCatching {
        val active = CustomUnitMetadata.activeCustomUnits
        val backing = active.toArray()
        List(backing.size) { backing[it] as? CustomUnitMetadata }
    }.getOrDefault(emptyList())
    activeCustomSnapshot.filterNotNull().forEach(types::add)

    return types.mapNotNull { type ->
        runCatching { type.internalName.takeIf(String::isNotBlank)?.let { it to type } }.getOrNull()
    }.distinctBy { it.first }.map { it.second }
}

internal fun submitSandboxDetachUnit(childId: Long) {
    GameScope.launch(GameMainDispatcher) {
        val engine = GameEngine.getInstance()
        if (!sandboxUnitDataUiAllowed(engine)) return@launch
        val child = runCatching { GameObject.getOrderableUnitFromId(childId, true) }
            .getOrNull()?.takeIf { it.id == childId && it !is EditorUnit } ?: return@launch
        if (child.attachedToUnit == null) return@launch
        val command = (engine.interfaceEngine as NativeSandboxEditorCommandAccess).rwppCreatePlayerCommand()
        val payload = command as NativeSandboxEditorPayloadAccess
        payload.rwppCommandUnits().clear()
        payload.rwppPendingUnitIds().clear()
        command.targetUnit = child
        command.hasSystemAction = true
        command.systemActionId = SANDBOX_DETACH_ACTION
        command.changeStepRate = 0f
        command.PENDING_systemCommandFloat = 0f
    }
}

internal fun submitSandboxDetachSlot(hostId: Long, slotIndex: Int) {
    val slotPayload = sandboxAttachmentSlotPayload(slotIndex) ?: return
    GameScope.launch(GameMainDispatcher) {
        val engine = GameEngine.getInstance()
        if (!sandboxUnitDataUiAllowed(engine)) return@launch
        val host = resolveSandboxEditableUnits(listOf(hostId)).singleOrNull() as? CustomUnit ?: return@launch
        val slot = host.getAttachmentSlotById(slotIndex.toShort()) ?: return@launch
        if (host.getUnitInAttachmentSlot(slot) == null) return@launch
        val command = (engine.interfaceEngine as NativeSandboxEditorCommandAccess).rwppCreatePlayerCommand()
        val payload = command as NativeSandboxEditorPayloadAccess
        payload.rwppCommandUnits().clear()
        payload.rwppPendingUnitIds().clear()
        command.targetUnit = host
        command.hasSystemAction = true
        command.systemActionId = SANDBOX_DETACH_SLOT_ACTION
        command.changeStepRate = 0f
        command.PENDING_systemCommandFloat = slotPayload
    }
}

/** Enters the native local editor and lets its normal update select the EditorUnit. */
internal fun submitSandboxOpenTerrainEditor() {
    GameScope.launch(GameMainDispatcher) {
        val engine = GameEngine.getInstance()
        if (!sandboxUnitDataUiAllowed(engine)) return@launch
        engine.debugEditorMode = true
        val ui = engine.interfaceEngine
        val access = ui as NativeSandboxTerrainInterfaceAccess
        val editor = access.rwppTerrainEditor()?.takeUnless { it.dead || it.removed }
            ?: EditorUnit(false).also {
                it.setTeamDirect(engine.playerTeam)
                access.rwppSetTerrainEditor(it)
            }
        (editor as NativeSandboxTerrainTabAccess).rwppSetTerrainTab(
            com.corrodinggames.rts.game.units.EditorUnitTab.terrain,
        )
        ui.cancelActiveAction()
        ui.clearUnitSelection()
        ui.selectUnit(editor)
    }
}

internal fun submitSandboxOwnerChange(unitIds: List<Long>, teamId: Int) {
    if (teamId !in 0..99) return
    submitSandboxUnitEdit(unitIds, SANDBOX_OWNER_ACTION_BASE + teamId, 0f)
}

internal fun submitSandboxAllianceChange(unitIds: List<Long>, groupId: Int) {
    if (groupId !in 0..4) return
    submitSandboxUnitEdit(unitIds, SANDBOX_ALLIANCE_ACTION_BASE + groupId, 0f)
}

internal fun submitSandboxUnitData(
    unitIds: List<Long>,
    edits: Map<SandboxUnitDataField, Float>,
    resourceEdits: List<SandboxUnitResourceEdit> = emptyList(),
) {
    if (edits.isEmpty() && resourceEdits.isEmpty()) return
    val selectionIds = unitIds.distinct().toList()
    val stableEdits = edits.toMap()
    val stableResourceEdits = resourceEdits.toList()
    GameScope.launch(GameMainDispatcher) {
        for (field in sandboxUnitDataApplyOrder) {
            stableEdits[field]?.takeIf(field::accepts)?.let { value ->
                queueSandboxUnitEdit(selectionIds, field.commandId, value)
            }
        }
        for ((internalName, value) in stableResourceEdits) {
            val payload = sandboxSplitResourceAmount(value) ?: continue
            val applicable = resolveSandboxEditableUnits(selectionIds).filterIsInstance<CustomUnit>()
                .filter { sandboxCanApplyUnitResource(it, internalName) }
            if (applicable.isNotEmpty()) {
                queueSandboxUnitEdit(
                    applicable.map { it.id },
                    sandboxUnitResourceActionId(internalName),
                    payload.commandFloat,
                    payload.commandStepRate,
                    payload.targetPointX,
                    payload.targetPointY,
                )
            }
        }
    }
}

private fun submitSandboxUnitEdit(unitIds: List<Long>, actionId: Int, value: Float) {
    val selectionIds = unitIds.distinct().toList()
    GameScope.launch(GameMainDispatcher) { queueSandboxUnitEdit(selectionIds, actionId, value) }
}

private fun queueSandboxUnitEdit(
    unitIds: List<Long>,
    actionId: Int,
    value: Float,
    commandStepRate: Float = 0f,
    targetPointX: Float? = null,
    targetPointY: Float? = null,
) {
    val engine = GameEngine.getInstance()
    if (!sandboxUnitDataUiAllowed(engine) || !isSandboxEditorAction(actionId)) return
    val dataField = sandboxUnitDataFieldForCommand(actionId)
    if (dataField != null && !dataField.accepts(value)) return
    if (isSandboxUnitResourceAction(actionId)) {
        if (targetPointX == null || targetPointY == null ||
            sandboxJoinResourceAmount(commandStepRate, value, targetPointX, targetPointY) == null
        ) return
    } else if (dataField == null && (!value.isFinite() || value != 0f || commandStepRate != 0f ||
                targetPointX != null || targetPointY != null)
    ) {
        return
    }
    val targets = resolveSandboxEditableUnits(unitIds)
    if (targets.isEmpty() || engine.playerTeam == null) return
    if (isSandboxUnitResourceAction(actionId) && sandboxResourceNameForAction(actionId, targets) == null) return

    val command = (engine.interfaceEngine as NativeSandboxEditorCommandAccess).rwppCreatePlayerCommand()
    targets.forEach(command::addUnit)
    command.hasSystemAction = true
    command.changeStepRate = commandStepRate
    command.systemActionId = actionId
    command.PENDING_systemCommandFloat = value
    if (targetPointX != null && targetPointY != null) {
        command.targetPoint = PointF(targetPointX, targetPointY)
    }
}

/** Applies only when the native simulation dispatches the replicated/recorded command. */
internal fun dispatchSandboxUnitEdit(command: CommandControllerCommand): Boolean {
    val actionId = command.systemActionId
    if (!isSandboxUnitEditAction(command.hasSystemAction, actionId)) return false

    val engine = GameEngine.getInstance()
    // Resource action IDs use a reserved negative marker. Never consume that range in network games.
    if (isSandboxUnitResourceAction(actionId) && engine.networkEngine.networked &&
        !engine.replayEngine.isPlayingReplay
    ) return false
    // Custom command IDs are only accepted in local single-player sandbox sessions or replays.
    // Multiplayer sandbox editing must use native command IDs to preserve peer compatibility.
    if (!shouldAcceptSandboxUnitEdit(
            isSandboxedLocalGame = sandboxUnitDataUiAllowed(engine),
            replaying = engine.replayEngine.isPlayingReplay,
        )
    ) return true

    if (actionId in SANDBOX_ATTACH_EXISTING_ACTION until SANDBOX_ATTACHMENT_ACTION_LIMIT) {
        dispatchSandboxAttachmentCommand(command, actionId)
        return true
    }

    val payload = command as NativeSandboxEditorPayloadAccess
    payload.rwppResolvePendingUnitIds()
    val targets = payload.rwppCommandUnits()
        .asSequence().filterIsInstance<OrderableUnit>()
        .filterNot { it is EditorUnit }
        .filterNot { it.dead }
        .distinctBy { it.id }
        .toList()
    if (targets.isEmpty()) return true

    if (isSandboxUnitResourceAction(actionId)) {
        val internalName = sandboxResourceNameForAction(actionId, targets) ?: return true
        val resource = CustomResource.getByName(internalName) ?: return true
        val encodedTarget = command.targetPoint ?: return true
        val value = sandboxJoinResourceAmount(
            command.changeStepRate,
            command.PENDING_systemCommandFloat,
            encodedTarget.x,
            encodedTarget.y,
        )
            ?: return true
        targets.filterIsInstance<CustomUnit>().forEach { unit ->
            applySandboxUnitResource(unit, resource, internalName, value)
        }
        com.corrodinggames.rts.gameFramework.gui.InterfaceEngine.markUiDirty()
        return true
    }

    when (actionId) {
        in SANDBOX_OWNER_ACTION_BASE until SANDBOX_OWNER_ACTION_LIMIT -> {
            val teamId = actionId - SANDBOX_OWNER_ACTION_BASE
            val team = Team.getTeam(teamId) ?: return true
            targets.forEach { it.setTeam(team) }
        }

        in SANDBOX_ALLIANCE_ACTION_BASE until SANDBOX_ALLIANCE_ACTION_LIMIT -> {
            val group = actionId - SANDBOX_ALLIANCE_ACTION_BASE
            targets.map { it.team }.distinct().forEach { it.allyTeam = group }
        }

        in SANDBOX_DATA_ACTION_BASE until SANDBOX_DATA_ACTION_LIMIT -> {
            val field = sandboxUnitDataFieldForCommand(actionId) ?: return true
            val value = command.PENDING_systemCommandFloat
            if (!field.accepts(value)) return true
            targets.forEach { applySandboxUnitData(it, field, value) }
        }
    }
    com.corrodinggames.rts.gameFramework.gui.InterfaceEngine.markUiDirty()
    return true
}

private fun dispatchSandboxAttachmentCommand(command: CommandControllerCommand, actionId: Int) {
    when (actionId) {
        SANDBOX_ATTACH_EXISTING_ACTION -> {
            val host = command.targetUnit as? CustomUnit ?: return
            val slotIndex = sandboxAttachmentSlotFromPayload(command.PENDING_systemCommandFloat) ?: return
            val payload = command as NativeSandboxEditorPayloadAccess
            payload.rwppResolvePendingUnitIds()
            val children = sandboxNativeListSnapshot(payload.rwppCommandUnits())
                .filterIsInstance<OrderableUnit>()
                .filterNot { it.dead || it is EditorUnit }
                .distinctBy { it.id }
            val child = children.singleOrNull() ?: return
            sandboxAttachToEmptySlot(host, child, slotIndex)
        }

        SANDBOX_ATTACH_SPAWN_ACTION -> {
            val host = command.targetUnit as? CustomUnit ?: return
            val slotIndex = sandboxAttachmentSlotFromPayload(command.PENDING_systemCommandFloat) ?: return
            val waypoint = command.waypoint ?: return
            if (waypoint.type != WaypointType.build) return
            val unitType = waypoint.unitType ?: return
            val slot = sandboxEmptyAttachmentSlot(host, slotIndex) ?: return
            val typeName = runCatching { unitType.internalName }.getOrNull() ?: return
            val unitsBefore = sandboxNativeUnitListSnapshot(GameUnit.unitList)
                .filterIsInstance<GameUnit>()
                .mapTo(HashSet()) { it.id }

            // Keep native UnitType/Waypoint serialization. System action 5 is the engine's normal
            // deterministic spawn path; the reserved action is restored below after it returns.
            val originalActionId = command.systemActionId
            val originalTeam = command.team
            val originalValue = command.PENDING_systemCommandFloat
            try {
                command.setBuildWaypoint(host.x, host.y, unitType, 0)
                command.team = host.team
                command.systemActionId = 5
                command.PENDING_systemCommandFloat = 0f
                command.issueCommand()
            } finally {
                command.systemActionId = originalActionId
                command.team = originalTeam
                command.PENDING_systemCommandFloat = originalValue
            }

            val newUnits = sandboxNativeUnitListSnapshot(GameUnit.unitList)
                .filterIsInstance<GameUnit>()
                .filterNot { it.id in unitsBefore }
            val spawnedRoot = newUnits.asSequence()
                .filter { runCatching { it.unitTypeInterface?.internalName == typeName }.getOrDefault(false) }
                .minByOrNull { it.id }
                ?: return
            val child = spawnedRoot as? OrderableUnit
            if (child == null || !sandboxAttachToSlot(host, child, slot)) {
                spawnedRoot.killAndRemove()
            }
        }

        SANDBOX_DETACH_ACTION -> {
            val child = command.targetUnit as? OrderableUnit ?: return
            if (child is EditorUnit) return
            val parent = child.attachedToUnit as? CustomUnit ?: return
            if (parent.deattachRequest(child) || child.attachedToUnit == null) return
            child.deattachFromParent()
        }

        SANDBOX_DETACH_SLOT_ACTION -> {
            val host = command.targetUnit as? CustomUnit ?: return
            val slotIndex = sandboxAttachmentSlotFromPayload(command.PENDING_systemCommandFloat) ?: return
            val slot = host.getAttachmentSlotById(slotIndex.toShort()) ?: return
            val child = host.getUnitInAttachmentSlot(slot) ?: return
            if (child.attachedToUnit !== host) return
            if (!host.deattachRequest(child) && child.attachedToUnit != null) child.deattachFromParent()
        }
    }
}

private fun sandboxEmptyAttachmentSlot(host: CustomUnit, index: Int): UnitAttachmentsAttachmentSlot? {
    if (host.dead || index !in 0..Short.MAX_VALUE) return null
    val slot = host.getAttachmentSlotById(index.toShort()) ?: return null
    if (slot.slotIndex.toInt() != index || host.getUnitInAttachmentSlot(slot) != null) return null
    return slot
}

private fun sandboxAttachToEmptySlot(
    host: CustomUnit,
    child: OrderableUnit,
    slotIndex: Int,
): Boolean {
    val slot = sandboxEmptyAttachmentSlot(host, slotIndex) ?: return false
    return sandboxAttachToSlot(host, child, slot)
}

private fun sandboxAttachToSlot(
    host: CustomUnit,
    child: OrderableUnit,
    slot: UnitAttachmentsAttachmentSlot,
): Boolean {
    if (sandboxAttachmentRejection(host, child) != SandboxAttachmentRejection.NONE) return false
    if (host.getUnitInAttachmentSlot(slot) != null) return false
    if (!host.attachRequest(child, slot)) return false
    val attachedCorrectly = child.attachedToUnit === host && host.getUnitInAttachmentSlot(slot) === child
    if (!attachedCorrectly) {
        if (child.attachedToUnit === host) host.deattachRequest(child)
        if (child.attachedToUnit === host) child.deattachFromParent()
    }
    return attachedCorrectly
}

internal fun applySandboxUnitData(unit: OrderableUnit, field: SandboxUnitDataField, value: Float) {
    if (!field.accepts(value)) return
    if (unit is CustomUnit) {
        val dynamicId = field.dynamicMetadataFieldId
        if (dynamicId != null) {
            val dynamicField = DynamicMetadata.getFieldById(dynamicId) ?: return
            val adjustedValue = when (field) {
                SandboxUnitDataField.HP -> value.coerceAtMost(unit.maxHp.coerceAtLeast(0f))
                SandboxUnitDataField.SHIELD -> value.coerceAtMost(unit.maxShield.coerceAtLeast(0f))
                else -> value
            }
            dynamicField.setValue(unit, adjustedValue.toDouble())
            when (field) {
                SandboxUnitDataField.MAX_HP -> if (unit.hp > unit.maxHp) unit.setHp(unit.maxHp)
                SandboxUnitDataField.MAX_SHIELD -> if (unit.shield > unit.maxShield) unit.shield = unit.maxShield
                SandboxUnitDataField.MAX_ENERGY -> if (unit.currentEnergy > unit.unitData.maxEnergy) {
                    unit.currentEnergy = unit.unitData.maxEnergy
                }

                else -> Unit
            }
            return
        }
    } else if (field.customOnly) {
        return
    }

    when (field) {
        SandboxUnitDataField.HP -> unit.setHp(value.coerceAtMost(unit.maxHp.coerceAtLeast(0f)))
        SandboxUnitDataField.MAX_HP -> if (value >= 1f) {
            unit.maxHp = value
            if (unit.hp > value) unit.setHp(value)
        }

        SandboxUnitDataField.SHIELD -> unit.shield = value.coerceAtMost(unit.maxShield.coerceAtLeast(0f))
        SandboxUnitDataField.MAX_SHIELD -> {
            unit.maxShield = value
            if (unit.shield > value) unit.shield = value
        }

        SandboxUnitDataField.ENERGY -> unit.currentEnergy = value
        SandboxUnitDataField.BUILD_PROGRESS -> unit.setBuildProgress(value.coerceIn(0f, 1f))
        SandboxUnitDataField.AMMO -> unit.ammo = value.toInt()
        SandboxUnitDataField.KILL_COUNT -> unit.killCount = value.toInt()
        SandboxUnitDataField.TOTAL_DAMAGE_DEALT -> unit.totalDamageDealt = value
        SandboxUnitDataField.FACING -> unit.setAngle(value)
        else -> Unit
    }
}

private fun sandboxUnitDataFieldForCommand(actionId: Int): SandboxUnitDataField? =
    SandboxUnitDataField.entries.firstOrNull { it.commandId == actionId }

internal fun shouldAcceptSandboxUnitEdit(isSandboxedLocalGame: Boolean, replaying: Boolean): Boolean =
    isSandboxedLocalGame || replaying

internal fun isSandboxUnitEditAction(isSystemAction: Boolean, actionId: Int): Boolean =
    isSystemAction && isSandboxEditorAction(actionId)

internal fun shouldForceEditorBuild(
    editorUnit: Boolean,
    placementAction: Boolean,
    sandbox: Boolean,
    optionEnabled: Boolean,
): Boolean = editorUnit && placementAction && sandbox && optionEnabled


internal interface NativeSandboxEditorCommandAccess {
    fun rwppCreatePlayerCommand(): CommandControllerCommand
}

internal interface NativeSandboxEditorPayloadAccess {
    fun rwppCommandUnits(): FastArrayList
    fun rwppPendingUnitIds(): FastArrayList
    fun rwppResolvePendingUnitIds()
}

internal interface NativeSandboxTerrainInterfaceAccess {
    fun rwppTerrainEditor(): EditorUnit?
    fun rwppSetTerrainEditor(editor: EditorUnit)
}

internal interface NativeSandboxTerrainTabAccess {
    fun rwppSetTerrainTab(tab: com.corrodinggames.rts.game.units.EditorUnitTab)
}
