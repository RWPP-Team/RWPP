/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

internal expect fun drawNativeWorld(engine: Any, canvas: Any?, frameDelta: Float)
internal expect fun drawNativeMap(map: com.corrodinggames.rts.game.map.Map, frameDelta: Float)

internal object WorldTextureFrame {
    private val filtered = ThreadLocal.withInitial { false }
    fun isFiltered(): Boolean = filtered.get()
    fun <T> draw(smooth: Boolean, block: () -> T): T {
        val previous = filtered.get()
        filtered.set(smooth)
        return try {
            block()
        } finally {
            filtered.set(previous)
        }
    }
}


internal data class WorldRenderFlagState(
    val extraShadows: Boolean,
    val fancyWater: Boolean,
    val clouds: Boolean,
    val antiAlias: Boolean
)

/** Shared lifetime logic is used by both native renderers; platform hooks provide field access. */
internal object WorldGraphicsEnhancements {
    @Volatile
    private var cachedSettings: Settings? = null

    private fun settings(): Settings = cachedSettings ?: synchronized(this) {
        cachedSettings ?: appKoin.get<Settings>().also { cachedSettings = it }
    }

    internal fun isEnhancedWorldRenderingEnabled(): Boolean = settings().enhancedWorldRendering
    internal fun isScreenSpaceReflectionsEnabled(): Boolean = settings().worldScreenSpaceReflections
    internal fun isScreenSpaceRayTracingEnabled(): Boolean = settings().worldScreenSpaceRayTracing
    internal fun screenSpaceRayTraceSteps(): Int = settings().worldRayTraceSteps.coerceIn(2, 8)
    internal fun worldLightingStrength(): Float = settings().worldLightingStrength.coerceIn(0f, 3f)
    internal fun waterWaveStrength(): Float = settings().worldWaterWaveStrength.coerceIn(0f, 1.5f)
    internal fun surfaceHighlightsEnabled(): Boolean = settings().worldSurfaceHighlights
    internal fun dynamicBloomEnabled(): Boolean = settings().worldDynamicBloom
    internal fun wetGroundStrength(): Float = settings().worldWetGroundStrength.coerceIn(0f, 2f)

    internal fun <T> withWorldQualityFlags(
        enhanced: Boolean,
        smoothTextures: Boolean,
        read: () -> WorldRenderFlagState,
        write: (WorldRenderFlagState) -> Unit,
        draw: () -> T
    ): T {
        if (!enhanced && !smoothTextures) return draw()
        val original = read()
        val quality = original.copy(
            extraShadows = original.extraShadows || enhanced,
            fancyWater = original.fancyWater || enhanced,
            // Clouds use the game's wrapped-tile renderer, which has a fixed 2,000-tile
            // failsafe. Enhanced lighting should not silently turn this separate option on.
            clouds = original.clouds,
            antiAlias = original.antiAlias || enhanced || smoothTextures
        )
        return try {
            write(quality)
            draw()
        } finally {
            write(original)
        }
    }

    internal fun <T> withNativeWorldQuality(
        engine: GameEngine,
        enhanced: Boolean,
        smoothTextures: Boolean,
        draw: () -> T
    ): T {
        val nativeSettings = engine.settings
        return withWorldQualityFlags(
            enhanced = enhanced,
            smoothTextures = smoothTextures,
            read = {
                WorldRenderFlagState(
                    extraShadows = nativeSettings.renderExtraShadows,
                    fancyWater = nativeSettings.renderFancyWater,
                    clouds = nativeSettings.renderClouds,
                    antiAlias = nativeSettings.renderAntiAlias
                )
            },
            write = { flags ->
                nativeSettings.renderExtraShadows = flags.extraShadows
                nativeSettings.renderFancyWater = flags.fancyWater
                nativeSettings.renderClouds = flags.clouds
                nativeSettings.renderAntiAlias = flags.antiAlias
            },
            draw = draw
        )
    }

    internal fun <T> withSmoothWorldTexture(paint: Paint, enabled: Boolean, draw: (Paint) -> T): T =
        if (enabled) WorldFilteredPaints.draw(paint, block = draw) else draw(paint)

    internal fun withEnhancedWorldDraw(engine: Any, canvas: Any?, frameDelta: Float) {
        val gameEngine = engine as GameEngine
        val currentSettings = settings()
        withNativeWorldQuality(gameEngine, currentSettings.enhancedWorldRendering, currentSettings.smoothGameTextures) {
            WorldTextureFrame.draw(currentSettings.smoothGameTextures) {
                drawNativeWorld(engine, canvas, frameDelta)
            }
        }
    }

    internal fun drawFilteredMapTile(
        graphics: GraphicsEngine,
        texture: BitmapOrTexture,
        source: Rect,
        destination: RectF,
        paint: Paint
    ) {
        // CanvasDirect applies the filter once at the final bitmap call, including cached tiles.
        if (!settings().smoothGameTextures || WorldTextureFrame.isFiltered() &&
            graphics.currentCanvas is com.corrodinggames.rts.gameFramework.render.CanvasDirect
        ) {
            graphics.drawImage(texture, source, destination, paint)
            return
        }
        withSmoothWorldTexture(paint, enabled = true) { filtered ->
            graphics.drawImage(texture, source, destination, filtered)
        }
    }
}
