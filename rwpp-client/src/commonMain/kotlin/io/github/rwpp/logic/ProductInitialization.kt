/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import io.github.rwpp.api.Initialization
import io.github.rwpp.core.Logic
import org.koin.core.annotation.Single

/** Registers product listeners before the API publishes its engine-ready event. */
@Single(binds = [Initialization::class])
class ProductInitialization : Initialization {
    private val initialized = java.util.concurrent.atomic.AtomicBoolean()
    override fun init() {
        if (!initialized.compareAndSet(false, true)) return
        Logic.init()
    }

    override fun onGameLoaded() = Logic.onGameLoaded()
}
