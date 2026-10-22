/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.api.Initialization
import io.github.rwpp.core.Logic
import io.github.rwpp.sound.Sounds
import org.koin.core.annotation.Single

/** Registers product listeners before the API publishes its engine-ready event. */
@Single(binds = [Initialization::class])
class ProductInitialization : Initialization {
    override fun init() {
        Sounds.init()
        Logic.init()
    }
}
