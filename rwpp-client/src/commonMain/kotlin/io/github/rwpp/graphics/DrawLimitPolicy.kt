/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.RectF
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

/** Bypass only the engine's near-viewport point test; fog and all other native checks remain. */
internal object DrawLimitPolicy {
    @Volatile
    private var settings: Settings? = null

    fun contains(rect: RectF, x: Float, y: Float): Boolean {
        val configured = settings ?: runCatching { appKoin.get<Settings>() }.getOrNull()?.also { settings = it }
        return configured?.removeDrawLimit == true && rect === GameEngine.getInstance().viewRectNearF || rect.contains(
            x,
            y
        )
    }
}
