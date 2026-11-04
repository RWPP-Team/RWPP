/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

/** The same live settings instance is consulted on both game render threads. */
internal object GameVisualSettings {
    @Volatile
    private var cached: Settings? = null
    fun get(): Settings? = cached ?: runCatching { appKoin.get<Settings>() }.getOrNull()?.also { cached = it }
    fun frameLimit(native: Int): Int = get()?.gameFrameLimit?.takeIf { it > 0 }?.coerceIn(15, 360) ?: native
    fun frameDuration(native: Long): Long = get()?.gameFrameLimit?.takeIf { it > 0 }
        ?.coerceIn(15, 360)?.let { 1_000_000_000L / it } ?: native
}

