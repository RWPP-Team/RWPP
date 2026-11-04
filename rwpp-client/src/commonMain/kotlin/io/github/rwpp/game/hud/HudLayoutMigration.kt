/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import io.github.rwpp.config.HudComponentData

/** Keep saved components, while merging the retired horizontal bar and detached minimap. */
internal fun normalizeHudComponents(components: MutableList<HudComponentData>) {
    // Move only the previous default anchor out from under the left menu icon.
    components.filter { it.id == "telemetry" && it.relativeX == 0.02f && it.relativeY == 0.01f }
        .forEach { it.relativeX = 0.06f }
    val console = components.firstOrNull { it.id == "hud_console_vertical" }
        ?: components.firstOrNull { it.id == "hud_console" }?.apply {
            id = "hud_console_vertical"
            relativeX = 1f
            relativeY = 0f
        }
    components.removeAll {
        it.id == "minimap" || it.id == "hud_console" ||
                it.id == "hud_console_vertical" && it !== console
    }
}
