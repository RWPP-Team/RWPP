/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.PlayerIds
import io.github.rwpp.api.logging.logger

/**
 * 本地队伍尚未分配时使用的占位队伍。
 *
 * 引擎自带的 blankPlayer 是一个 teamId 为 -1 的真实 Team，界面可以照常读取它的字段，
 * 不需要为此再单独造一个假的玩家实现。
 */
val noPlayer: Team
    get() = Team.blankPlayer

/**
 * RWPP 通过 ASM 混入到游戏 Team 上的私有状态。
 *
 * 连接编号与自定义数据没有引擎侧的存放位置，只能挂在 Team 实例上；其余玩家属性都由
 * Team 自身或 GameEngine 直接提供。
 */
interface PlayerState {
    var _connectHexId: String?
}

private val Team.playerState: PlayerState
    get() = this as PlayerState

/** 玩家所在的房间。改写玩家配置要先判断本机是不是房主或代理服。 */
val Team.room: GameRoom
    get() = appKoin.get<Game>().gameRoom

val Team.connectHexId: String
    get() = clientId ?: run {
        val state = playerState
        val current = state._connectHexId
        state._connectHexId =
            if (current == null || current == "0") PlayerIds.next().toString() else current
        state._connectHexId!!
    }

var Team.spawnPoint: Int
    get() = teamId
    set(value) {
        if (room.isHost) setTeamIdForced(value)
        else if (room.isHostServer) {
            GameEngine.getInstance().networkEngine.sendMoveTeamCommand(this, value, allyTeam)
        }
    }

var Team.team: Int
    get() = allyTeam
    set(value) {
        val network = GameEngine.getInstance().networkEngine
        if (room.isHost) allyTeam = value
        else if (room.isHostServer) network.sendSetTeamCommand(this, value)
    }

/** 玩家名。引擎允许为空，界面侧需要判空。 */
val Team.playerName: String
    get() = name ?: ""

/** 延迟的展示文本，例如 "50ms"。 */
val Team.pingStatus: String
    get() = getPingStatusText()

var Team.startingUnit: Int
    get() = startingUnitsOverride ?: -1
    set(value) {
        if (room.isHost) startingUnitsOverride = value.takeUnless { it == -1 }
    }

var Team.color: Int
    get() = colorOverride ?: -1
    set(value) {
        if (room.isHost) colorOverride = value.takeUnless { it == -1 }
    }

var Team.difficulty: Int?
    get() = if (isAI) aiDifficultyOverride else null
    set(value) {
        if (room.isHost) aiDifficultyOverride = value
    }

val Team.income: Int
    get() = aiDifficultyArmyValue

val Team.client: Connection?
    get() = GameEngine.getInstance().networkEngine.getConnectionForTeam(this)

fun Team.realDifficulty(): Int = if (isAI) effectiveAIDifficulty else 0

var Team.ready: Boolean
    get() = io.github.rwpp.api.net.packets.ModTransferHost.isReady(this)
    set(value) { io.github.rwpp.api.net.packets.ModTransferHost.setReady(this, value) }

fun Team.teamAlias(): String = when {
    allyTeam == -3 -> "S"
    allyTeam <= 10 -> Char('A'.code + allyTeam).toString()
    else -> (allyTeam + 1).toString()
}

fun Team.hasCredits(credits: Double): Boolean = this.credits >= credits || credits == 0.0

fun Team.aiCreditsMultiplier(): Float {
    if (!isAI) return 1.0f
    val realDifficulty = realDifficulty()
    var multiplier = if (realDifficulty > 0) {
        1.0f + (realDifficulty * 0.4f)
    } else {
        1.0f + (realDifficulty * 0.3f)
    }

    if (realDifficulty == 3) {
        multiplier += 1.5f
    }

    return multiplier.coerceAtLeast(0.1f)
}

fun Team.addCredits(creditsValue: Double) {
    var creditsIncrement = creditsValue * room.incomeMultiplier
    creditsIncrement *= if (isAI) aiCreditsMultiplier() else 1.0f

    credits += creditsIncrement
    if (credits > 9.99999999E8) {
        credits = 9.99999999E8
    }
}

fun Team.applyConfigChange(
    spawnPoint: Int = teamId,
    team: Int = allyTeam,
    color: Int? = null,
    startingUnits: Int? = null,
    aiDifficulty: Int? = null,
    autoTeamMode: Boolean = false,
) {
    val engine = GameEngine.getInstance()
    val network = engine.networkEngine
    var slot = spawnPoint
    var teamChanged = false
    var teamId = 0
    var autoAssigned = false
    var spectator = false

    if (slot == -3) {
        spectator = true
    } else {
        if (slot < 0) slot = 1
        if (slot > Team.maxTeamId - 1) slot = Team.maxTeamId - 1
    }

    if (spectator) {
        teamId = -3
        autoAssigned = true
    } else if (autoTeamMode) {
        teamId = slot % 2
        val currentTeam = Team.getTeam(spawnPoint)
        if (currentTeam != null) {
            teamId = currentTeam.allyTeam
        } else if (room.teamMode != null) {
            teamId = room.teamMode!!.autoTeamAssign(room, spawnPoint, this)
        }

        explicitAllyTeam = false
        autoAssigned = true
    } else {
        teamId = team - 1
        explicitAllyTeam = true
    }

    if (allyTeam != teamId) {
        if (room.isHost) {
            teamChanged = true
        } else if (network.proxyController || network.localTeam === this) {
            teamChanged = true
        }
    }

    try {
        if (this.teamId != slot) {
            if (room.isHost) {
                teamChanged = false
                network.moveTeamToSlot(this, slot)
                allyTeam = teamId
            } else if (network.proxyController || network.localTeam === this) {
                teamChanged = false
                val requestedTeam = if (autoAssigned) -1 else teamId
                network.sendMoveTeamCommand(this, slot, requestedTeam)
            }
        }
    } catch (failure: NumberFormatException) {
        logger().error(failure.stackTraceToString())
    }

    if (isAI) {
        val difficulty = aiDifficulty ?: -99
        val override = difficulty.takeUnless { it == -99 }
        if (this.aiDifficultyOverride != override) {
            if (room.isHost) aiDifficultyOverride = override
        }
    }

    startingUnits?.let { value ->
        val override = value.takeUnless { it == -99 }
        if (this.startingUnitsOverride != override && room.isHost) {
            startingUnitsOverride = override
        }
    } ?: run {
        startingUnitsOverride = null
    }

    color?.let { value ->
        val override = value.takeUnless { it == -99 }
        if (colorOverride != override && room.isHost) colorOverride = override
    } ?: run {
        colorOverride = null
    }

    if (teamChanged) {
        if (room.isHost) {
            allyTeam = teamId
        } else if (autoAssigned) {
            network.sendSetTeamCommand(this, -1)
        } else {
            network.sendSetTeamCommand(this, teamId)
        }
    }

    network.updateAIDifficulty()
    network.assignTeamDisplaySlots()
}
