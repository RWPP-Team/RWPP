package io.github.rwpp.ui

import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTurretInstance
import com.corrodinggames.rts.game.units.land.Tank
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import com.corrodinggames.rts.gameFramework.utility.GameObjectList
import com.corrodinggames.rts.java.JavaBitmap
import com.corrodinggames.rts.java.JavaCanvas
import io.github.rwpp.config.Settings
import io.github.rwpp.graphics.*
import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import org.newdawn.slick.Graphics
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.corrodinggames.rts.game.map.Map as NativeMap

/** Runs the production unit-material mask pass on a native JavaCanvas pbuffer, including cache hits. */
class WorldMaterialReuseNativeGlTest {
    private class AccessEngine : RWGameEngine(null), NativeWorldAccess {
        lateinit var objects: GameObjectList
        override fun rwppVisibleObjects() = objects
        override fun rwppFramesPerSecond() = 60
        override fun rwppDrawWorld(canvas: com.corrodinggames.rts.gameFramework.render.CanvasLike?, delta: Float) {
            drawGame(canvas, delta)
        }
    }

    @Test
    fun drawVisibleReusesStableGpuMaskAndInvalidatesWhenItsInputsChange() {
        val nativeDir = locateLwjglNativeDirectory()
        Assume.assumeTrue("Steam lwjgl64.dll was not found; offscreen native GL is unavailable", nativeDir != null)

        val previousNativePath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val pbuffer = try {
            Pbuffer(SIZE, SIZE, PixelFormat().withAlphaBits(8).withDepthBits(0), null, null, ContextAttribs(3, 0))
        } catch (failure: Throwable) {
            restoreNativePath(previousNativePath)
            Assume.assumeTrue("LWJGL could not create an OpenGL pbuffer: ${failure.javaClass.simpleName}", false)
            return
        }

        var renderer: JavaCanvas? = null
        var terrainMask: JavaBitmap? = null
        var replacementTerrainMask: JavaBitmap? = null
        var bodySprite: JavaBitmap? = null
        var turretSprite: JavaBitmap? = null
        var javaCanvasStateCaptured = false
        var previousShader: Any? = null
        var previousGraphics: Any? = null
        var engineInstanceField: Field? = null
        var previousEngineInstance: Any? = null
        var tankStaticValues: Map<Field, Any?>? = null
        var unitState: UnitMaterialStateSnapshot? = null
        var terrainState: TerrainStateSnapshot? = null
        var settingsState: FieldValueSnapshot? = null
        try {
            try {
                pbuffer.makeCurrent()
            } catch (failure: Throwable) {
                Assume.assumeTrue("LWJGL pbuffer could not become current: ${failure.javaClass.simpleName}", false)
                return
            }
            val version = GL11.glGetString(GL11.GL_VERSION).orEmpty()
            val numbers = Regex("(\\d+)\\.(\\d+)").find(version)?.groupValues
                ?.drop(1)?.mapNotNull(String::toIntOrNull).orEmpty()
            Assume.assumeTrue(
                "OpenGL 3.0 is required by #version 130; pbuffer reports '$version'",
                numbers.size >= 2 && numbers[0] >= 3
            )

            previousShader = JavaCanvas.currentShader
            previousGraphics = JavaCanvas.lastBoundGraphics
            javaCanvasStateCaptured = true
            JavaCanvas.currentShader = null
            JavaCanvas.lastBoundGraphics = null

            renderer = JavaCanvas().apply {
                shadersEnabled = true
                width = SIZE
                height = SIZE
                slickGraphics = Graphics(SIZE, SIZE)
            }

            unitState = snapshotUnitMaterialState()
            terrainState = snapshotTerrainState()
            settingsState = snapshotField(WorldGraphicsEnhancements, "cachedSettings")
            isolateUnitMaterialState()

            val nativeMap = allocateUnsafe(NativeMap::class.java).apply {
                tileWidth = MASK_SIZE
                tileHeight = MASK_SIZE
                mapWidthInTiles = MASK_SIZE
                mapHeightInTiles = MASK_SIZE
                fogOfWarEnabled = false
                mapLoaded = true
            }
            terrainMask = createTerrainMask(renderer!!, water = false)
            replacementTerrainMask = createTerrainMask(renderer!!, water = true)
            bodySprite = createTankBodySprite(renderer!!)
            turretSprite = createTankTurretSprite(renderer!!)
            setField(DesktopWorldTerrainMaterials, "map", nativeMap)
            setField(DesktopWorldTerrainMaterials, "mask", terrainMask)

            val tankAssetFields = listOf("bodyImage", "bodyTeamColorImages", "turretImage")
                .map { field(Tank::class.java, it) }
            tankStaticValues = tankAssetFields.associateWith { it.get(null) }
            field(Tank::class.java, "bodyImage").set(null, bodySprite!!)
            field(Tank::class.java, "bodyTeamColorImages").set(null, Array<BitmapOrTexture>(10) { bodySprite!! })
            field(Tank::class.java, "turretImage").set(null, turretSprite!!)

            val testSettings = Settings(enhancedWorldRendering = true)
            setField(WorldGraphicsEnhancements, "cachedSettings", testSettings)

            val engine = allocateUnsafe(AccessEngine::class.java) as GameEngine
            field(GameEngine::class.java, "gameUpdateLock").set(engine, Any())
            engine.gameLoopActive = true
            engine.graphics = renderer!!
            engine.screenWidth = SIZE.toFloat()
            engine.screenHeight = SIZE.toFloat()
            engine.viewWidth = SIZE.toFloat()
            engine.viewHeight = SIZE.toFloat()
            engine.cameraRenderX = 0f
            engine.cameraRenderY = 0f
            engine.zoomScale = 1f
            engine.map = nativeMap
            engineInstanceField = field(GameEngine::class.java, "al")
            previousEngineInstance = engineInstanceField!!.get(null)
            engineInstanceField!!.set(null, engine)
            engine.playerTeam = Team.blankPlayer

            val unit = allocateUnsafe(Tank::class.java).apply {
                team = Team.blankPlayer
                x = 32f
                y = 32f
                spriteWidth = SPRITE_FRAME_SIZE
                spriteHeight = SPRITE_FRAME_SIZE
                halfWidth = SPRITE_FRAME_SIZE / 2f
                halfHeight = SPRITE_FRAME_SIZE / 2f
                turrets = arrayOf(UnitTurretInstance().apply { angle = 0f })
            }
            val objects = GameObjectList("allOnScreenObjects")
            field(GameObjectList::class.java, "items").set(objects, arrayOf(unit as GameObject))
            field(GameObjectList::class.java, "size").setInt(objects, 1)
            (engine as AccessEngine).objects = objects
            val animationFrameField = field(Tank::class.java, "moveAnimationFrame")

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "test setup must leave a clean GL error state")
            var stableDrawNanos = 0L
            repeat(STABLE_FRAME_COUNT) { frame ->
                val drawStarted = System.nanoTime()
                DesktopWorldUnitMaterials.drawVisible(engine)
                stableDrawNanos += System.nanoTime() - drawStarted
                checkSlickPbufferBoundaryAndPostDraw(allowFboBoundaryError = frame == 0)
            }

            val stable = DesktopWorldUnitMaterials.renderStats()
            assertEquals(
                STABLE_FRAME_COUNT.toLong(), stable.framesConsidered,
                "the production drawVisible entry point must consider all stable calls"
            )
            assertEquals(
                1L, stable.framesDrawn,
                "a static one-unit scene should generate its terrain/sprite mask once"
            )
            assertEquals(
                STABLE_FRAME_COUNT - 1L, stable.framesReused,
                "the remaining stable frames should reuse the GPU mask"
            )
            assertEquals(
                STABLE_FRAME_COUNT.toLong(), stable.unitsSampled,
                "each frame signature should inspect the real Tank"
            )
            assertEquals(
                STABLE_FRAME_COUNT.toLong(), stable.turretSlotsSampled,
                "each frame signature should inspect the real Tank turret"
            )
            assertEquals(1L, stable.bodySpritesDrawn, "the native pass must draw one real Tank body")
            assertEquals(1L, stable.turretSpritesDrawn, "the native pass must draw one real Tank turret")
            val renderedMaterial = assertNotNull(
                DesktopWorldUnitMaterials.texture() as? JavaBitmap,
                "the successful draw pass should publish its GPU mask"
            )
            val materialPixel = readTexturePixel(renderedMaterial, SIZE / 2, SIZE / 2)
            val bodyPixel = readTexturePixel(renderedMaterial, 25, 25)
            val terrainPixel = readTexturePixel(renderedMaterial, 10, 10)
            assertTrue(
                materialPixel[2] > 245 && bodyPixel[2] > 245,
                "the GPU readback should contain both the turret and body alpha masks: " +
                        "turret=${materialPixel.contentToString()} body=${bodyPixel.contentToString()}"
            )
            assertTrue(
                terrainPixel[2] < 5 && terrainPixel[1] in 124..132,
                "the terrain background should retain its coast-distance channel outside the unit: ${terrainPixel.contentToString()}"
            )
            assertEquals(255, materialPixel[3], "the rendered material texture should be opaque")
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "postdraw texture readback must leave GL clean")
            var precedingMaskPixels = readTexturePixels(renderedMaterial)

            val invalidationResults = mutableListOf<String>()
            fun verifyInputChange(label: String, change: () -> Unit, restore: () -> Unit) {
                val before = DesktopWorldUnitMaterials.renderStats()
                change()
                val changedFrameStarted = System.nanoTime()
                DesktopWorldUnitMaterials.drawVisible(engine)
                val changedFrameNanos = System.nanoTime() - changedFrameStarted
                checkSlickPbufferBoundaryAndPostDraw(allowFboBoundaryError = true)
                val after = DesktopWorldUnitMaterials.renderStats()
                assertEquals(
                    before.framesDrawn + 1, after.framesDrawn,
                    "$label change must redraw the production GPU mask"
                )
                assertEquals(
                    before.framesReused, after.framesReused,
                    "$label change must not be counted as a reuse"
                )
                assertEquals(
                    before.bodySpritesDrawn + 1, after.bodySpritesDrawn,
                    "$label change must redraw the native unit body"
                )
                assertEquals(
                    before.turretSpritesDrawn + 1, after.turretSpritesDrawn,
                    "$label change must redraw the native unit turret"
                )
                if (label == "camera") assertEquals(
                    before.unitsSampled + 1, after.unitsSampled,
                    "camera motion must capture the unit signature in the same traversal that draws the mask"
                )
                val changedMaskPixels = readTexturePixels(renderedMaterial)
                assertTrue(
                    !precedingMaskPixels.contentEquals(changedMaskPixels),
                    "$label change must alter the actual rendered GPU mask"
                )
                precedingMaskPixels = changedMaskPixels
                invalidationResults += "$label:drawn+1,reused+0,elapsed_ns=$changedFrameNanos"
                val restoreBefore = DesktopWorldUnitMaterials.renderStats()
                restore()
                DesktopWorldUnitMaterials.drawVisible(engine)
                checkSlickPbufferBoundaryAndPostDraw(allowFboBoundaryError = true)
                val restored = DesktopWorldUnitMaterials.renderStats()
                assertEquals(
                    restoreBefore.framesDrawn + 1, restored.framesDrawn,
                    "restoring the prior $label input should also rebuild the mask"
                )
                assertEquals(
                    restoreBefore.framesReused, restored.framesReused,
                    "restoring the prior $label input should not reuse a different frame"
                )
                assertEquals(
                    restoreBefore.bodySpritesDrawn + 1, restored.bodySpritesDrawn,
                    "restoring $label should redraw the native unit body"
                )
                assertEquals(
                    restoreBefore.turretSpritesDrawn + 1, restored.turretSpritesDrawn,
                    "restoring $label should redraw the native unit turret"
                )
                if (label == "camera") assertEquals(
                    restoreBefore.unitsSampled + 1, restored.unitsSampled,
                    "return camera motion must capture its signature during the mask traversal"
                )
                val restoredMaskPixels = readTexturePixels(renderedMaterial)
                assertTrue(
                    !precedingMaskPixels.contentEquals(restoredMaskPixels),
                    "restoring $label should change the rendered mask back to that input"
                )
                precedingMaskPixels = restoredMaskPixels
            }

            verifyInputChange("camera", { engine.cameraRenderX = 3f }, { engine.cameraRenderX = 0f })
            val settledCameraBefore = DesktopWorldUnitMaterials.renderStats()
            DesktopWorldUnitMaterials.drawVisible(engine)
            val settledCamera = DesktopWorldUnitMaterials.renderStats()
            assertEquals(
                settledCameraBefore.framesDrawn, settledCamera.framesDrawn,
                "the first stationary frame after panning must reuse the mask keyed during motion"
            )
            assertEquals(
                settledCameraBefore.framesReused + 1, settledCamera.framesReused,
                "the first stationary frame after panning must be a cache hit"
            )
            assertEquals(
                settledCameraBefore.unitsSampled + 1, settledCamera.unitsSampled,
                "the stationary cache check must capture the unit key before reusing"
            )
            DesktopWorldUnitMaterials.drawVisible(engine)
            val stableAfterPan = DesktopWorldUnitMaterials.renderStats()
            assertEquals(
                settledCamera.framesReused + 1, stableAfterPan.framesReused,
                "subsequent stationary frames should continue to reuse the camera-keyed mask"
            )
            assertEquals(
                settledCamera.unitsSampled + 1, stableAfterPan.unitsSampled,
                "each stationary cache check must inspect the visible unit key"
            )
            verifyInputChange("zoom", { engine.zoomScale = 1.25f }, { engine.zoomScale = 1f })
            verifyInputChange("terrain texture", {
                setField(DesktopWorldTerrainMaterials, "mask", replacementTerrainMask)
            }, {
                setField(DesktopWorldTerrainMaterials, "mask", terrainMask)
            })
            verifyInputChange("unit movement", { unit.x = 38f }, { unit.x = 32f })
            verifyInputChange(
                "animation frame",
                { animationFrameField.setInt(unit, 1) },
                { animationFrameField.setInt(unit, 0) })
            verifyInputChange("turret angle", { unit.turrets[0].angle = 37f }, { unit.turrets[0].angle = 0f })

            val finalStats = DesktopWorldUnitMaterials.renderStats()
            val result = buildString {
                appendLine("drawVisible native GL reuse regression")
                appendLine(
                    "stable_calls=${STABLE_FRAME_COUNT}; frames_considered=${stable.framesConsidered}; " +
                            "frames_drawn=${stable.framesDrawn}; frames_reused=${stable.framesReused}"
                )
                appendLine(
                    "stable_draw_visible_elapsed_ns=$stableDrawNanos; " +
                            "stable_draw_visible_mean_ns=${stableDrawNanos / STABLE_FRAME_COUNT}"
                )
                appendLine(
                    "stable_signature_capture_ns=${stable.signatureCaptureNanos}; " +
                            "units_sampled=${stable.unitsSampled}; turret_slots_sampled=${stable.turretSlotsSampled}"
                )
                appendLine(
                    "gpu_mask_pixels=turret:${materialPixel.contentToString()}, " +
                            "body:${bodyPixel.contentToString()}, terrain:${terrainPixel.contentToString()}"
                )
                appendLine("invalidation_checks=${invalidationResults.joinToString(", ")}")
                append(
                    "total_frames_considered=${finalStats.framesConsidered}; " +
                            "total_frames_drawn=${finalStats.framesDrawn}; " +
                            "total_frames_reused=${finalStats.framesReused}; " +
                            "total_signature_capture_ns=${finalStats.signatureCaptureNanos}"
                )
            }
            val output = Path.of("build/ui-regression/world-unit-material-reuse-native-gl.txt")
            Files.createDirectories(output.parent)
            Files.writeString(output, result)
            assertTrue(Files.size(output) > 0, "the native GL regression should emit its counters and timings")
            assertNotNull(
                DesktopWorldUnitMaterials.texture(),
                "the successful draw pass should publish its mask texture"
            )
        } finally {
            runCatching { renderer?.flushBitmap() }
            if (javaCanvasStateCaptured) {
                JavaCanvas.currentShader = null
                JavaCanvas.lastBoundGraphics = null
            }
            runCatching { renderer?.makeCurrent() }
            if (unitState != null) {
                val currentImage = getField(DesktopWorldUnitMaterials, "image") as? BitmapOrTexture
                val currentCanvas = getField(DesktopWorldUnitMaterials, "canvas") as? GraphicsEngine
                val savedImage = unitState!!.values[field(DesktopWorldUnitMaterials::class.java, "image")]
                val savedCanvas = unitState!!.values[field(DesktopWorldUnitMaterials::class.java, "canvas")]
                val shaders = listOf("program", "background").mapNotNull { name ->
                    (getField(DesktopWorldUnitMaterials, name) as? PaintGLShader)?.let { name to it }
                }.filter { (name, shader) ->
                    shader !== unitState!!.values[field(DesktopWorldUnitMaterials::class.java, name)]
                }.distinctBy { it.second.programId }

                if (currentCanvas != null && currentCanvas !== savedCanvas) runCatching { currentCanvas.PENDING_noop_q() }
                if (currentImage != null && currentImage !== savedImage) runCatching { currentImage.disposeBitmap() }
                runCatching { GL20.glUseProgram(0) }
                for ((_, shader) in shaders) {
                    if (shader.programId != 0) runCatching { GL20.glDeleteProgram(shader.programId) }
                    if (shader.vertexShaderId != 0) runCatching { GL20.glDeleteShader(shader.vertexShaderId) }
                    if (shader.fragmentShaderId != 0) runCatching { GL20.glDeleteShader(shader.fragmentShaderId) }
                }
            }
            runCatching { unitState?.let(::restoreUnitMaterialState) }
            runCatching { terrainState?.let(::restoreTerrainState) }
            runCatching { settingsState?.let(::restoreField) }
            runCatching { tankStaticValues?.forEach { (savedField, savedValue) -> savedField.set(null, savedValue) } }
            runCatching { engineInstanceField?.set(null, previousEngineInstance) }
            runCatching { terrainMask?.disposeBitmap() }
            runCatching { replacementTerrainMask?.disposeBitmap() }
            runCatching { bodySprite?.disposeBitmap() }
            runCatching { turretSprite?.disposeBitmap() }
            runCatching { renderer?.slickGraphics?.destroy() }
            if (javaCanvasStateCaptured) {
                JavaCanvas.currentShader = previousShader as? com.corrodinggames.rts.gameFramework.render.PaintGLShader
                JavaCanvas.lastBoundGraphics = previousGraphics as? Graphics
            }
            runCatching { pbuffer.destroy() }
            restoreNativePath(previousNativePath)
        }
    }

    private data class FieldValueSnapshot(val field: Field, val target: Any, val value: Any?)
    private data class RectSnapshot(val reference: Rect, val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class RectFSnapshot(
        val reference: RectF,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class SignatureSnapshot(
        val reference: WorldUnitMaterialFrameSignature,
        val contents: WorldUnitMaterialFrameSignature
    )

    private data class MapSnapshot(val reference: ConcurrentHashMap<*, *>, val contents: Map<*, *>)
    private data class UnitMaterialStateSnapshot(val values: Map<Field, Any?>)
    private data class TerrainStateSnapshot(val map: FieldValueSnapshot, val mask: FieldValueSnapshot)

    private fun snapshotUnitMaterialState(): UnitMaterialStateSnapshot {
        val values = DesktopWorldUnitMaterials::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associateWith { candidate ->
                candidate.isAccessible = true
                val value = candidate.get(DesktopWorldUnitMaterials)
                when (value) {
                    is Rect -> RectSnapshot(value, value.left, value.top, value.right, value.bottom)
                    is RectF -> RectFSnapshot(value, value.left, value.top, value.right, value.bottom)
                    is WorldUnitMaterialFrameSignature -> SignatureSnapshot(
                        value,
                        WorldUnitMaterialFrameSignature().also { it.copyFrom(value) })

                    is ConcurrentHashMap<*, *> -> MapSnapshot(value, value.toMap())
                    else -> value
                }
            }
        return UnitMaterialStateSnapshot(values)
    }

    private fun isolateUnitMaterialState() {
        for (name in listOf("background", "backgroundPaint", "image", "canvas", "program", "paint")) {
            setField(DesktopWorldUnitMaterials, name, null)
        }
        for (name in listOf("ready", "failed", "cachedFrameSignatureValid", "signatureCameraValid")) {
            setField(DesktopWorldUnitMaterials, name, false)
        }
        for (name in listOf(
            "framesConsidered", "framesReused", "framesDrawn", "bodySpritesDrawn",
            "turretSpritesDrawn", "signatureCaptureNanos", "unitsSampled", "turretSlotsSampled"
        )) {
            setField(DesktopWorldUnitMaterials, name, 0L)
        }
        (getField(DesktopWorldUnitMaterials, "frameSignature") as WorldUnitMaterialFrameSignature).reset()
        (getField(DesktopWorldUnitMaterials, "cachedFrameSignature") as WorldUnitMaterialFrameSignature).reset()
    }

    private fun restoreUnitMaterialState(snapshot: UnitMaterialStateSnapshot) {
        for ((candidate, saved) in snapshot.values) {
            candidate.isAccessible = true
            when (saved) {
                is RectSnapshot -> saved.reference.set(saved.left, saved.top, saved.right, saved.bottom)
                is RectFSnapshot -> saved.reference.set(saved.left, saved.top, saved.right, saved.bottom)
                is SignatureSnapshot -> saved.reference.copyFrom(saved.contents)
                is MapSnapshot -> {
                    @Suppress("UNCHECKED_CAST")
                    val target = saved.reference as ConcurrentHashMap<Any?, Any?>
                    target.clear()
                    target.putAll(saved.contents as Map<Any?, Any?>)
                }

                else -> if (!Modifier.isFinal(candidate.modifiers)) candidate.set(DesktopWorldUnitMaterials, saved)
            }
        }
    }

    private fun snapshotTerrainState() = TerrainStateSnapshot(
        snapshotField(DesktopWorldTerrainMaterials, "map"),
        snapshotField(DesktopWorldTerrainMaterials, "mask")
    )

    private fun restoreTerrainState(snapshot: TerrainStateSnapshot) {
        restoreField(snapshot.map)
        restoreField(snapshot.mask)
    }

    private fun snapshotField(target: Any, name: String): FieldValueSnapshot {
        val field = field(target.javaClass, name)
        return FieldValueSnapshot(field, target, field.get(target))
    }

    private fun restoreField(snapshot: FieldValueSnapshot) = snapshot.field.set(snapshot.target, snapshot.value)

    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
    private fun getField(target: Any, name: String): Any? = field(target.javaClass, name).get(target)
    private fun setField(target: Any, name: String, value: Any?) = field(target.javaClass, name).set(target, value)

    private fun createTerrainMask(renderer: JavaCanvas, water: Boolean): JavaBitmap {
        val bitmap = createTerrainMaskImage(renderer, MASK_SIZE, MASK_SIZE) as JavaBitmap
        bitmap.ensurePixelData()
        val pixel = terrainMaterialMaskPixel(water, if (water) 3f else 0f, lava = false)
        for (y in 0 until MASK_SIZE) for (x in 0 until MASK_SIZE) bitmap.setPixel(x, y, pixel)
        bitmap.commitPixelData()
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "terrain texture upload should not cause GL errors")
        return bitmap
    }

    private fun createTankBodySprite(renderer: JavaCanvas): JavaBitmap {
        val bitmap = createTerrainMaskImage(renderer, SPRITE_FRAME_SIZE * 2, SPRITE_FRAME_SIZE) as JavaBitmap
        bitmap.ensurePixelData()
        for (frame in 0..1) for (y in 0 until SPRITE_FRAME_SIZE) for (x in 0 until SPRITE_FRAME_SIZE) {
            val hole = if (frame == 0) x in 4..11 && y in 4..11 else x in 2..9 && y in 4..11
            val color = when {
                hole -> 0x00000000
                frame == 0 -> 0xffe04030.toInt()
                else -> 0xff3050e0.toInt()
            }
            bitmap.setPixel(frame * SPRITE_FRAME_SIZE + x, y, color)
        }
        bitmap.commitPixelData()
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "native body sprite upload should not cause GL errors")
        return bitmap
    }

    private fun createTankTurretSprite(renderer: JavaCanvas): JavaBitmap {
        val bitmap = createTerrainMaskImage(renderer, TURRET_SIZE, TURRET_SIZE) as JavaBitmap
        bitmap.ensurePixelData()
        for (y in 0 until TURRET_SIZE) for (x in 0 until TURRET_SIZE) {
            val opaque = x in 2..5 || (y in 5..7 && x <= 5)
            bitmap.setPixel(x, y, if (opaque) 0xffffd040.toInt() else 0x00000000)
        }
        bitmap.commitPixelData()
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "native turret sprite upload should not cause GL errors")
        return bitmap
    }

    private fun readTexturePixel(bitmap: JavaBitmap, x: Int, y: Int): IntArray {
        val texture = bitmap.slickImage.texture
        val buffer = BufferUtils.createByteBuffer(texture.textureWidth * texture.textureHeight * 4)
        val activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.textureID)
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            val index = (y * texture.textureWidth + x) * 4
            return IntArray(4) { buffer.get(index + it).toInt() and 0xff }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding)
            GL13.glActiveTexture(activeTexture)
        }
    }

    private fun readTexturePixels(bitmap: JavaBitmap): IntArray {
        val texture = bitmap.slickImage.texture
        val buffer = BufferUtils.createByteBuffer(texture.textureWidth * texture.textureHeight * 4)
        val activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val previousBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.textureID)
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "GPU mask readback should not cause GL errors")
            return IntArray(buffer.capacity()) { buffer.get(it).toInt() and 0xff }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousBinding)
            GL13.glActiveTexture(activeTexture)
        }
    }

    private fun checkSlickPbufferBoundaryAndPostDraw(allowFboBoundaryError: Boolean) {
        // Slick FBOGraphics.unbind hardcodes GL_BACK, while the regression pbuffer is single
        // buffered. Permit that one known boundary error, then require a clean postdraw state.
        val error = GL11.glGetError()
        if (allowFboBoundaryError && error == GL11.GL_INVALID_OPERATION && !GL11.glGetBoolean(GL11.GL_DOUBLEBUFFER)) {
            assertEquals(
                GL11.GL_NO_ERROR,
                GL11.glGetError(),
                "only the known single-buffer FBO boundary error is allowed"
            )
        } else {
            assertEquals(GL11.GL_NO_ERROR, error, "unexpected GL error after drawVisible")
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "postdraw GL state must be clean")
    }

    private fun <T : Any> allocateUnsafe(type: Class<T>): T {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(type) as T
    }

    private fun locateLwjglNativeDirectory(): Path? {
        val roots = buildList {
            System.getProperty("rwpp.lwjgl.nativePath")?.let { add(Path.of(it)) }
            System.getenv("RWPP_LWJGL_NATIVE_PATH")?.let { add(Path.of(it)) }
            System.getenv("ProgramFiles(x86)")?.let { add(Path.of(it).resolve("Steam")) }
            System.getenv("ProgramFiles")?.let { add(Path.of(it).resolve("Steam")) }
            System.getenv("STEAM_ROOT")?.let { add(Path.of(it)) }
            add(Path.of("D:/Steam")); add(Path.of("D:/steam"))
        }
        for (root in roots.distinct()) {
            listOf(
                root,
                root.resolve("steamapps/common/Rusted Warfare"),
                root.resolve("steamapps/common/rusted_warfare")
            )
                .firstOrNull { Files.isRegularFile(it.resolve("lwjgl64.dll")) }?.let { return it }
            val libraries = root.resolve("steamapps/libraryfolders.vdf")
            if (Files.isRegularFile(libraries)) {
                val contents = runCatching { Files.readString(libraries) }.getOrDefault("")
                Regex("\\\"path\\\"\\s*\\\"([^\\\"]+)\\\"").findAll(contents).forEach { match ->
                    val game = Path.of(match.groupValues[1].replace("\\\\", "/"))
                        .resolve("steamapps/common/Rusted Warfare")
                    if (Files.isRegularFile(game.resolve("lwjgl64.dll"))) return game
                }
            }
        }
        return null
    }

    private fun restoreNativePath(previous: String?) {
        if (previous == null) System.clearProperty("org.lwjgl.librarypath") else System.setProperty(
            "org.lwjgl.librarypath",
            previous
        )
    }

    private companion object {
        const val SIZE = 64
        const val MASK_SIZE = 8
        const val SPRITE_FRAME_SIZE = 16
        const val TURRET_SIZE = 8
        const val STABLE_FRAME_COUNT = 120
    }
}
