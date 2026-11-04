/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

internal const val WORLD_PROJECTED_SHADOW_ALPHA = 96

/** Keep wave phase precision and frame-delta handling identical on both native renderers. */
internal fun advanceWorldLightingTime(previous: Float, frameDelta: Float): Float =
    (previous + frameDelta.coerceIn(0f, 6f) / 60f) % 10000f
