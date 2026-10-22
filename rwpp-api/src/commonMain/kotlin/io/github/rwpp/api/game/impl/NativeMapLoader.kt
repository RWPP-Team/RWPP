/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType

/** Engine-side map loading logic copied from MapSelectActivity without its Android Activity wrapper. */
internal object NativeMapLoader {
    private val playersInMapPattern = Regex("^ *\\[([^\\]]*)\\].*")

    fun isSkirmishMap(path: String): Boolean = path.contains("skirmish/")

    fun loadMapWithAI(
        path: String,
        isSkirmishMap: Boolean,
        playerCount: Int,
        allyCount: Int,
        useAllies: Boolean,
        skipLoad: Boolean,
    ) {
        val engine = GameEngine.getInstance()
        engine.interfaceEngine.clearMessageLogs()
        if (!isSkirmishMap && !skipLoad) {
            engine.setStopped()
            synchronized(engine) {
                engine.inputNetStream = null
                engine.currentMapPath = path
            }
            if (!skipLoad) engine.loadMap(true, GameEngineMapType.normal)
            return
        }

        var alliesCreated = 0
        engine.setStopped()
        synchronized(engine) {
            engine.inputNetStream = null
            engine.currentMapPath = path

            var lastTeamId = Team.maxTeamId - 1
            val mapPlayerCount = getNumberOfPlayersInMap(path)
            GameEngine.log("Max teams on map: $path = $mapPlayerCount")
            if (mapPlayerCount > 0 && mapPlayerCount - 1 < lastTeamId) {
                lastTeamId = mapPlayerCount - 1
            }

            Team.clearAllTeams()
            engine.playerTeam = Player(0)
            engine.playerTeam.name = "Player"

            for (side in 0..1) {
                for (teamId in 1..lastTeamId) {
                    val isAlly = teamId % 2 == 0 || side == 1
                    if (alliesCreated < allyCount && isAlly && Team.getTeam(teamId) == null) {
                        AI(teamId).apply {
                            name = "AI"
                            allyTeam = 0
                        }
                        alliesCreated++
                    }
                }
            }

            GameEngine.log("Allies: $alliesCreated/$allyCount")
            var enemiesCreated = 0
            val enemiesToCreate = playerCount - allyCount

            for (side in 0..1) {
                for (teamId in 1..lastTeamId) {
                    var isEnemy = teamId % 2 == 1 || side == 1
                    if (!useAllies) isEnemy = true

                    if (enemiesCreated < enemiesToCreate && isEnemy && Team.getTeam(teamId) == null) {
                        AI(teamId).apply {
                            name = "AI"
                            if (useAllies) allyTeam = 1
                        }
                        enemiesCreated++
                    }
                }
            }

            engine.networkEngine.updateNamesOfAI()
            if (!skipLoad) engine.loadMap(false, GameEngineMapType.normal)
        }
    }

    private fun getNumberOfPlayersInMap(path: String?): Int {
        val fileName = path?.substringAfterLast('/')?.substringAfterLast('\\')
        val match = fileName?.let(playersInMapPattern::matchEntire)
        val metadata = match?.groupValues?.getOrNull(1)
        if (metadata != null) {
            for (field in metadata.split(';')) {
                if (field.startsWith('p') && field.length >= 2) {
                    val value = field.drop(1)
                    return value.toIntOrNull() ?: run {
                        GameEngine.log("getNumberOfPlayersInMap: NumberFormatException:$value")
                        -1
                    }
                }
            }
        }
        GameEngine.log("getNumberOfPlayersInMap: fail to match:$fileName")
        return -1
    }
}
