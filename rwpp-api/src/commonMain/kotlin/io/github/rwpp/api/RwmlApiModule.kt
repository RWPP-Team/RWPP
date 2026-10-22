/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api

import io.github.rwpp.api.game.mode.GameModes
import io.github.rwpp.api.game.team.TeamModes
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/** The built-in RWML API extension's Koin entry point. */
@Module
@ComponentScan("io.github.rwpp.api")
class RwmlApiModule {
    @Single
    fun teamModes(): TeamModes = TeamModes

    @Single
    fun gameModes(): GameModes = GameModes
}
