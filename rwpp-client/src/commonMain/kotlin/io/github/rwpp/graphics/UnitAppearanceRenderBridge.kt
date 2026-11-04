/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Rect
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.TeamColorMode
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.BitmapOrTextureLazyColoring
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.GameVisualSettings

/** Local draw-time overrides only. Never writes native Team colors or Unit ownership. */
internal object UnitAppearanceRenderBridge {
    data class CacheSnapshot(
        val paintedTextureCount: Int,
        val outlinedTextureCount: Int,
        val outlinedPixelCount: Long,
        val paintedColorsArgb: Set<Int>,
        val outlineColorsArgb: Set<Int>,
        val sourceTextureIds: Set<Int>,
    )

    private data class VariantSource(
        val source: BitmapOrTexture,
        val mode: TeamColorMode,
        val originalSlot: Int,
    )

    private data class TextureKey(
        val source: BitmapOrTexture,
        val sourceVersion: Int,
        val mode: TeamColorMode,
        val color: Int,
        val teamId: Int,
    )

    data class DrawOverride(val texture: BitmapOrTexture, val source: Rect?)

    private data class OutlineKey(
        val source: BitmapOrTexture,
        val sourceVersion: Int,
        val mode: TeamColorMode,
        val color: Int,
        val colorSlot: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class RawSettings(
        val paintEnabled: Boolean,
        val playerMap: String,
        val triColorEnabled: Boolean,
        val selfColor: String,
        val enemyColor: String,
        val allyColor: String,
        val ringsEnabled: Boolean,
        val ringSelf: String,
        val ringEnemy: String,
        val ringAlly: String,
        val ringNeutral: String,
        val outlineEnabled: Boolean,
    )

    private data class SettingsSnapshot(
        val paintEnabled: Boolean,
        val playerMap: List<UnitAppearanceColorSource>,
        val triColorEnabled: Boolean,
        val selfColor: UnitAppearanceColorSource,
        val enemyColor: UnitAppearanceColorSource,
        val allyColor: UnitAppearanceColorSource,
        val ringsEnabled: Boolean,
        val ringSelf: Int,
        val ringEnemy: Int,
        val ringAlly: Int,
        val ringNeutral: Int,
        val outlineEnabled: Boolean,
    )

    private data class RenderContext(val unit: Unit, val settings: SettingsSnapshot)

    private val context = ThreadLocal.withInitial<RenderContext?> { null }
    private val lock = Any()
    private val variantSources = LinkedHashMap<Int, VariantSource>()
    private val coloredTextures = LinkedHashMap<TextureKey, BitmapOrTextureLazyColoring>()
    private val outlinedTextures = LinkedHashMap<OutlineKey, BitmapOrTexture>()
    private val pendingOutlines = mutableSetOf<OutlineKey>()
    private val failedOutlines = linkedSetOf<OutlineKey>()
    private var outlinedPixelCount = 0L
    private var previousRawSettings: RawSettings? = null
    private var previousSettings: SettingsSnapshot? = null

    /** Scope the full virtual Unit.draw call, including subclass drawing after super.draw returns. */
    fun <T> withUnit(unit: Unit, selection: Boolean = false, block: () -> T): T {
        val previous = context.get()
        val settings = snapshot(GameVisualSettings.get())
        if (settings == null || (!settings.paintEnabled && !settings.outlineEnabled &&
                    !(selection && settings.ringsEnabled)
                    )
        ) context.remove()
        else context.set(RenderContext(unit, settings))
        return try {
            block()
        } finally {
            if (previous == null) context.remove() else context.set(previous)
        }
    }

    fun registerTeamColorSources(
        source: BitmapOrTexture,
        mode: TeamColorMode,
        variants: Array<BitmapOrTexture>,
    ) = synchronized(lock) {
        variants.forEachIndexed { index, variant ->
            variantSources[variant.id] = VariantSource(source, mode, index)
        }
        while (variantSources.size > MAX_SOURCE_ENTRIES) {
            variantSources.remove(variantSources.keys.first())
        }
    }

    /** Read-only evidence for native probes; does not trigger rendering or alter settings. */
    fun cacheSnapshot(): CacheSnapshot = synchronized(lock) {
        CacheSnapshot(
            paintedTextureCount = coloredTextures.size,
            outlinedTextureCount = outlinedTextures.size,
            outlinedPixelCount = outlinedPixelCount,
            paintedColorsArgb = coloredTextures.keys.mapTo(linkedSetOf()) { it.color },
            outlineColorsArgb = outlinedTextures.keys.mapTo(linkedSetOf()) { it.color },
            sourceTextureIds = buildSet {
                coloredTextures.keys.forEach { add(it.source.id) }
                outlinedTextures.keys.forEach { add(it.source.id) }
            },
        )
    }

    /** Called at native Canvas draw time, while an OrderableUnit/CustomUnit draw is in scope. */
    fun drawOverride(texture: BitmapOrTexture, sourceRect: Rect? = null): DrawOverride? {
        val current = context.get() ?: return null
        val source = synchronized(lock) { variantSources[texture.id] } ?: return null
        val settings = current.settings
        val team = current.unit.team ?: return null
        val displaySlot = team.getDisplaySlot().coerceIn(0, 9)
        val ownerPosition = team.teamId
        val ownerNativeColor = team.getColor()
        val paintColor = resolveUnitAppearancePaintColor(
            enabled = settings.paintEnabled,
            triColorEnabled = settings.triColorEnabled,
            relation = relation(current.unit),
            ownerPosition = ownerPosition,
            ownerNativeColor = ownerNativeColor,
            playerColorMap = settings.playerMap,
            selfSource = settings.selfColor,
            enemySource = settings.enemyColor,
            allySource = settings.allyColor,
            nativeColorForPlayerPosition = { playerPosition ->
                Team.getTeam(playerPosition)?.getColor() ?: Team.lookupTeamColor(playerPosition)
            },
        ) ?: ownerNativeColor

        if (settings.outlineEnabled) {
            val frame = sourceRect?.let { Rect(it) } ?: Rect(0, 0, texture.width, texture.height)
            val outlined = outlineTexture(source, frame, paintColor)
            if (outlined != null) {
                return DrawOverride(outlined, Rect(0, 0, frame.width(), frame.height()))
            }
        }

        val painted = paintedTexture(texture, source, settings, displaySlot, paintColor)
        return if (painted === texture) null else DrawOverride(painted, sourceRect)
    }

    private fun paintedTexture(
        texture: BitmapOrTexture,
        source: VariantSource,
        settings: SettingsSnapshot,
        displaySlot: Int,
        paintColor: Int,
    ): BitmapOrTexture {
        if (!settings.paintEnabled || source.mode == TeamColorMode.disabled ||
            paintColor == Team.lookupTeamColor(source.originalSlot)
        ) return texture
        val key = TextureKey(source.source, source.source.version, source.mode, paintColor, displaySlot)
        synchronized(lock) {
            coloredTextures[key]?.let { cached ->
                coloredTextures.remove(key)
                coloredTextures[key] = cached
                return cached
            }
            val colored = BitmapOrTextureLazyColoring(source.source, paintColor, source.mode, displaySlot)
            colored.name = "RWPP local paint ${source.source.name} #${paintColor.toString(16)}"
            coloredTextures[key] = colored
            while (coloredTextures.size > MAX_COLORED_ENTRIES) {
                coloredTextures.remove(coloredTextures.keys.first())
            }
            return colored
        }
    }

    /** CPU-cached one-pixel edge texture. It is applied to sprite pixels, not drawn as a ring overlay. */
    private fun outlineTexture(
        source: VariantSource,
        frame: Rect,
        color: Int,
    ): BitmapOrTexture? {
        val left = frame.left.coerceIn(0, source.source.width)
        val top = frame.top.coerceIn(0, source.source.height)
        val right = frame.right.coerceIn(0, source.source.width)
        val bottom = frame.bottom.coerceIn(0, source.source.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_OUTLINE_PIXEL_COUNT ||
            source.source.width.toLong() * source.source.height > MAX_OUTLINE_PIXEL_COUNT
        ) return null
        val key = OutlineKey(
            source.source, source.source.version, source.mode, color, source.originalSlot,
            left, top, right, bottom,
        )
        val pixelCost = width.toLong() * height
        synchronized(lock) {
            outlinedTextures[key]?.let { cached ->
                return cached
            }
            if (key in failedOutlines || key in pendingOutlines || outlinedTextures.size >= MAX_OUTLINE_ENTRIES ||
                outlinedPixelCount + pixelCost > MAX_OUTLINE_PIXEL_COUNT
            ) return null
            pendingOutlines += key
            outlinedPixelCount += pixelCost
        }

        val attempt = runCatching {
            val basePixels = sourcePixels(source, color)
            val framePixels = IntArray(width * height) { index ->
                val x = left + index % width
                val y = top + index / width
                basePixels[y * source.source.width + x]
            }
            val output = framePixels.copyOf()
            val outlineRgb = color and 0x00FFFFFF
            val requestedAlpha = (color ushr 24) and 0xFF
            val outlineAlpha = requestedAlpha * 0.78f
            for (y in 0 until height) for (x in 0 until width) {
                val index = y * width + x
                if (((framePixels[index] ushr 24) and 0xFF) != 0) continue
                var adjacentAlpha = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) {
                        adjacentAlpha = maxOf(adjacentAlpha, (framePixels[ny * width + nx] ushr 24) and 0xFF)
                    }
                }
                if (adjacentAlpha > 0) {
                    val alpha = (outlineAlpha * adjacentAlpha / 255f).toInt().coerceIn(1, 255)
                    output[index] = (alpha shl 24) or outlineRgb
                }
            }

            val result = GameEngine.getInstance().graphics.createImageImpl(width, height, true).also { image ->
                image.setName("RWPP local outline ${source.source.name}")
                image.ensurePixelData()
                for (index in output.indices) image.setPixel(index % width, index / width, output[index])
                image.commitPixelData()
            }
            synchronized(lock) {
                pendingOutlines.remove(key)
                outlinedTextures[key] = result
            }
            result
        }
        if (attempt.isFailure) {
            val failure = attempt.exceptionOrNull()
            synchronized(lock) {
                pendingOutlines.remove(key)
                if (failedOutlines.size >= MAX_FAILED_OUTLINE_ENTRIES) {
                    failedOutlines.remove(failedOutlines.first())
                }
                failedOutlines += key
                outlinedPixelCount = (outlinedPixelCount - pixelCost).coerceAtLeast(0L)
            }
            GameEngine.log(
                "RWPP unit outline skipped for ${source.source.name}: ${failure?.message ?: "native pixel data unavailable"}"
            )
            return null
        }
        return attempt.getOrThrow()
    }

    private fun sourcePixels(source: VariantSource, color: Int): IntArray {
        val sourceAlreadyHasPixels = source.source.pixelData != null
        if (source.mode == TeamColorMode.disabled) {
            source.source.ensurePixelData()
            return try {
                IntArray(source.source.width * source.source.height) {
                    source.source.getPixel(it % source.source.width, it / source.source.width)
                }
            } finally {
                if (!sourceAlreadyHasPixels) source.source.freePixelData()
            }
        }
        source.source.ensurePixelData()
        val colored = source.source.copy()
        return try {
            colored.loadPixelData()
            val output = arrayOf(colored)
            when (source.mode) {
                TeamColorMode.hueAdd -> Team.applyHueAddColoring(source.source, output, intArrayOf(color))
                TeamColorMode.hueShift -> Team.applyHueShiftColoring(
                    source.source, output, intArrayOf(color), intArrayOf(source.originalSlot)
                )

                else -> Team.applyHueNewColoring(source.source, output, intArrayOf(color))
            }
            colored.commitPixelData()
            IntArray(colored.width * colored.height).also {
                for (index in it.indices) it[index] = colored.getPixel(index % colored.width, index / colored.width)
            }
        } finally {
            colored.disposeBitmap()
            if (!sourceAlreadyHasPixels) source.source.freePixelData()
        }
    }

    fun selectionRingColor(): Int? {
        val current = context.get() ?: return null
        return resolveUnitAppearanceRingColor(
            enabled = current.settings.ringsEnabled,
            relation = relation(current.unit),
            selfColor = current.settings.ringSelf,
            enemyColor = current.settings.ringEnemy,
            allyColor = current.settings.ringAlly,
            neutralColor = current.settings.ringNeutral,
        )
    }

    private fun relation(unit: Unit): UnitAppearanceRelation {
        val team = unit.team ?: return UnitAppearanceRelation.NEUTRAL
        if (team === Team.grayTeam) return UnitAppearanceRelation.NEUTRAL
        val player = runCatching { com.corrodinggames.rts.gameFramework.GameEngine.getInstance().playerTeam }
            .getOrNull() ?: return UnitAppearanceRelation.ALLY
        return when {
            team === player -> UnitAppearanceRelation.SELF
            player.isEnemy(team) -> UnitAppearanceRelation.ENEMY
            else -> UnitAppearanceRelation.ALLY
        }
    }

    private fun snapshot(settings: Settings?): SettingsSnapshot? {
        settings ?: return null
        val raw = RawSettings(
            settings.unitAppearancePaintEnabled,
            settings.unitAppearancePlayerColorMap,
            settings.unitAppearanceTriColorEnabled,
            settings.unitAppearanceSelfColor,
            settings.unitAppearanceEnemyColor,
            settings.unitAppearanceAllyColor,
            settings.unitAppearanceSelectionRingsEnabled,
            settings.unitAppearanceRingSelfColor,
            settings.unitAppearanceRingEnemyColor,
            settings.unitAppearanceRingAllyColor,
            settings.unitAppearanceRingNeutralColor,
            settings.unitAppearanceOutlineBrighteningEnabled,
        )
        synchronized(lock) {
            if (raw == previousRawSettings) return previousSettings
            return SettingsSnapshot(
                paintEnabled = raw.paintEnabled,
                playerMap = parseUnitAppearancePlayerColorMap(raw.playerMap),
                triColorEnabled = raw.triColorEnabled,
                selfColor = parseUnitAppearanceColorSource(raw.selfColor) ?: UnitAppearanceColorSource.Position,
                enemyColor = parseUnitAppearanceColorSource(raw.enemyColor) ?: UnitAppearanceColorSource.Position,
                allyColor = parseUnitAppearanceColorSource(raw.allyColor) ?: UnitAppearanceColorSource.Position,
                ringsEnabled = raw.ringsEnabled,
                ringSelf = parseRingColor(raw.ringSelf, DEFAULT_RING_SELF),
                ringEnemy = parseRingColor(raw.ringEnemy, DEFAULT_RING_ENEMY),
                ringAlly = parseRingColor(raw.ringAlly, DEFAULT_RING_ALLY),
                ringNeutral = parseRingColor(raw.ringNeutral, DEFAULT_RING_NEUTRAL),
                outlineEnabled = raw.outlineEnabled,
            ).also { previousRawSettings = raw; previousSettings = it }
        }
    }

    private fun parseRingColor(text: String, fallback: Int): Int =
        (parseUnitAppearanceColorSource(text) as? UnitAppearanceColorSource.Fixed)?.argb ?: fallback

    private const val MAX_SOURCE_ENTRIES = 4096
    private const val MAX_COLORED_ENTRIES = 256
    private const val MAX_OUTLINE_ENTRIES = 512
    private const val MAX_FAILED_OUTLINE_ENTRIES = 512
    private const val MAX_OUTLINE_PIXEL_COUNT = 8_388_608L
    private const val DEFAULT_RING_SELF = 0xB400FF00.toInt()
    private const val DEFAULT_RING_ENEMY = 0xB4FF0000.toInt()
    private const val DEFAULT_RING_ALLY = 0xB4FFFF00.toInt()
    private const val DEFAULT_RING_NEUTRAL = 0xB45A5A5A.toInt()
}
