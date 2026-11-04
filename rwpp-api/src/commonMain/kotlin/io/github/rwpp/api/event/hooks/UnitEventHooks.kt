/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.event.events.*
import java.util.*

private data class UnitDamageSnapshot(val unit: Unit, val health: Float, val shield: Float)
private data class BuildProgressSnapshot(val unit: Unit, val progress: Float)
private data class UnitTeamSnapshot(val unit: Unit, val team: Team?)
private data class UnitKillSnapshot(val unit: Unit, val wasDead: Boolean)
private data class UnitRemovalSnapshot(val unit: Unit, val wasRemoved: Boolean)

internal object UnitEventCallbacks {
    private val damageFrames = ThreadLocal.withInitial { ArrayDeque<UnitDamageSnapshot>() }
    private val buildProgressFrames = ThreadLocal.withInitial { ArrayDeque<BuildProgressSnapshot>() }
    private val teamFrames = ThreadLocal.withInitial { ArrayDeque<UnitTeamSnapshot>() }
    private val killFrames = ThreadLocal.withInitial { ArrayDeque<UnitKillSnapshot>() }
    private val removalFrames = ThreadLocal.withInitial { ArrayDeque<UnitRemovalSnapshot>() }

    fun stageSpawnCandidate(target: Any, isPlaceholder: Boolean) {
        if (!isPlaceholder && hasNativeEventListeners(UnitSpawnedEvent::class)) {
            NativeUnitSpawnRegistry.stage(target as Unit)
        }
    }

    fun captureDamage(
        target: Any, @Suppress("UNUSED_PARAMETER") attacker: Any?, @Suppress("UNUSED_PARAMETER") damage: Float,
        @Suppress("UNUSED_PARAMETER") projectile: Any?
    ) {
        val unit = target as Unit
        damageFrames.get().addLast(UnitDamageSnapshot(unit, unit.hp, unit.shield))
    }

    fun publishDamage(
        target: Any,
        attacker: Any?,
        requestedDamage: Float,
        projectile: Any?,
        remainingDamage: Float,
    ) {
        val unit = target as Unit
        val frames = damageFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) damageFrames.remove()
        if (before.unit !== unit) return

        val healthDamage = (before.health - unit.hp).coerceAtLeast(0.0F)
        val shieldDamage = (before.shield - unit.shield).coerceAtLeast(0.0F)
        if (healthDamage == 0.0F && shieldDamage == 0.0F) return

        if (hasNativeEventListeners(UnitDamagedEvent::class)) {
            val damage = UnitDamage(
                unit = unit,
                attacker = attacker as? Unit,
                projectile = projectile as? Projectile,
                requestedDamage = requestedDamage,
                healthDamage = healthDamage,
                shieldDamage = shieldDamage,
                remainingDamage = remainingDamage,
            )
            broadcastNativeEvent(UnitDamagedEvent(damage))
        }

        val engine = GameEngine.getInstance()
        if (unit.team == engine.playerTeam) {
            if (unit.isBuilding) GameEventPublisher.localPlayerBaseDamaged()
            else GameEventPublisher.localPlayerUnitDamaged()
        }
    }

    fun captureBuildProgress(target: Any, @Suppress("UNUSED_PARAMETER") progress: Float) {
        val unit = target as Unit
        buildProgressFrames.get().addLast(BuildProgressSnapshot(unit, unit.buildProgress))
    }

    fun publishConstructionCompleted(target: Any, @Suppress("UNUSED_PARAMETER") progress: Float) {
        val unit = target as Unit
        val frames = buildProgressFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) buildProgressFrames.remove()
        if (before.unit !== unit || before.progress >= 1.0F || unit.buildProgress < 1.0F) return

        publishCreated(unit)
        if (hasNativeEventListeners(UnitConstructionCompletedEvent::class)) {
            broadcastNativeEvent(UnitConstructionCompletedEvent(unit))
        }
    }

    fun captureTeamChange(target: Any, @Suppress("UNUSED_PARAMETER") team: Any?) {
        val unit = target as Unit
        teamFrames.get().addLast(UnitTeamSnapshot(unit, unit.team))
    }

    fun publishTeamChange(target: Any, @Suppress("UNUSED_PARAMETER") team: Any?) {
        val unit = target as Unit
        val frames = teamFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) teamFrames.remove()
        val nextTeam = unit.team ?: return
        if (before.unit !== unit || before.team === nextTeam) return
        if (hasNativeEventListeners(UnitTeamChangedEvent::class)) {
            broadcastNativeEvent(UnitTeamChangedEvent(unit))
        }
    }

    fun captureKill(target: Any) {
        val unit = target as Unit
        killFrames.get().addLast(UnitKillSnapshot(unit, unit.dead))
    }

    fun publishDeath(target: Any) {
        val unit = target as Unit
        val frames = killFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) killFrames.remove()
        if (before.unit !== unit || before.wasDead || !unit.dead) return
        if (hasNativeEventListeners(UnitDiedEvent::class)) {
            broadcastNativeEvent(UnitDiedEvent(unit))
        }
    }

    fun captureRemoval(target: Any) {
        val unit = target as Unit
        removalFrames.get().addLast(UnitRemovalSnapshot(unit, unit.removed))
    }

    fun publishRemoval(target: Any) {
        val unit = target as Unit
        val frames = removalFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) removalFrames.remove()
        if (before.unit === unit && !before.wasRemoved && unit.removed) {
            NativeUnitSpawnRegistry.forget(unit)
            if (hasNativeEventListeners(UnitRemovedEvent::class)) {
                broadcastNativeEvent(UnitRemovedEvent(unit))
            }
        }
    }

    fun publishCommittedUnits() {
        NativeUnitSpawnRegistry.publishCommitted()
    }

    fun publishAppliedDeferredUnits() {
        NativeUnitSpawnRegistry.publishCommitted()
    }
}

internal object UnitUpgradeEventCallbacks {
    fun publishUpgrade(target: Any) {
        val unit = target as OrderableUnit
        if (hasNativeEventListeners(UnitUpgradedEvent::class)) {
            broadcastNativeEvent(UnitUpgradedEvent(unit))
        }
        if (unit.team == GameEngine.getInstance().playerTeam) {
            GameEventPublisher.localPlayerUpgradeCompleted()
        }
    }
}

internal object FactoryUnitCreationEventCallbacks {
    fun publishFactoryUnitCreated(unit: Unit?) {
        if (unit != null) publishCreated(unit)
    }
}

internal object UnitSpawnRegistryCallbacks {
    fun clearCommittedUnits() {
        NativeUnitSpawnRegistry.clear()
    }
}

private object NativeUnitSpawnRegistry {
    private val lock = Any()
    private val pending: MutableSet<Unit> = Collections.newSetFromMap(IdentityHashMap<Unit, Boolean>())

    fun stage(unit: Unit) {
        synchronized(lock) { pending.add(unit) }
    }

    fun publishCommitted() {
        if (!hasNativeEventListeners(UnitSpawnedEvent::class)) {
            clear()
            return
        }

        val candidates = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toList().also { pending.clear() }
        }

        for (unit in candidates) {
            if (!unit.removed && !unit.isPlaceholder) {
                broadcastNativeEvent(UnitSpawnedEvent(unit))
            }
        }
    }

    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    fun forget(unit: Unit) {
        synchronized(lock) { pending.remove(unit) }
    }
}

private fun publishCreated(unit: Any) {
    val nativeUnit = unit as Unit
    if (hasNativeEventListeners(UnitCreatedEvent::class)) {
        broadcastNativeEvent(UnitCreatedEvent(nativeUnit))
    }
    val engine = GameEngine.getInstance()
    if (nativeUnit.team == engine.playerTeam) {
        if (nativeUnit.isBuilding) GameEventPublisher.localPlayerBuildingConstructed()
        else GameEventPublisher.localPlayerUnitCreated()
    }
}
