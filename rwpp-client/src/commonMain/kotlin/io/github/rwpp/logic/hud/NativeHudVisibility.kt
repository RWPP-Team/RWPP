/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.hud

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

internal object GameUiPauseState {
    @Volatile
    var blocked = false
}

internal object NativeHudVisibility {
    @Volatile
    var hidden = false

    @Volatile
    var aeroSelection = false

    @Volatile
    var aeroOrders = false

    @Volatile
    var aeroRallyPoints = false

    fun update() {
        val settings = appKoin.get<Settings>()
        hidden = settings.hideOriginalHud
        aeroSelection = settings.aeroSelection
        aeroOrders = settings.aeroOrderEffects
        aeroRallyPoints = settings.aeroRallyPointEffects
    }
}
