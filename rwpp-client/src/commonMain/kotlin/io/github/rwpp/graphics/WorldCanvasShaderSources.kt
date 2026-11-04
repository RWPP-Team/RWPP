/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

/** The exact desktop fragment programs adapted for Android RuntimeShader / Skia RuntimeEffect. */
internal object WorldCanvasShaderSources {
    val TERRAIN by lazy { WorldShaderDialect.sksl(WorldTerrainShaderSources.FRAGMENT_SHADER, terrainMask = true) }
    val POST by lazy { WorldShaderDialect.sksl(WorldPostShaderSources.FRAGMENT_SHADER) }
    val SHADOW by lazy { WorldShaderDialect.sksl(WorldShadowShaderSources.FRAGMENT_SHADER) }
    val UNIT_MASK by lazy { WorldShaderDialect.sksl(WorldUnitShaderSources.FRAGMENT_SHADER) }
    val BACKGROUND by lazy {
        WorldShaderDialect.sksl(
            WorldUnitShaderSources.BACKGROUND_FRAGMENT_SHADER,
            backgroundMask = true
        )
    }
}
