package io.github.rwpp.ui

import android.graphics.Paint
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import io.github.rwpp.graphics.*
import io.github.rwpp.mixin.graphics.*
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class WorldGraphicsEnhancementsTest {
    @Test
    fun nativeWorldQualityFlagsAreScopedAndRestoredWhenDrawingFails() {
        var flags = WorldRenderFlagState(
            extraShadows = false,
            fancyWater = true,
            clouds = false,
            antiAlias = false
        )

        assertFailsWith<IllegalStateException> {
            WorldGraphicsEnhancements.withWorldQualityFlags(
                enhanced = true,
                smoothTextures = true,
                read = { flags },
                write = { flags = it }
            ) {
                assertTrue(flags.extraShadows)
                assertTrue(flags.fancyWater)
                assertFalse(flags.clouds)
                assertTrue(flags.antiAlias)
                error("native world draw failed")
            }
        }

        assertEquals(WorldRenderFlagState(false, true, false, false), flags)
    }

    @Test
    fun disabledWorldQualityDoesNotTouchNativePreferences() {
        val original = WorldRenderFlagState(true, false, false, true)
        var flags = original
        var drew = false

        WorldGraphicsEnhancements.withWorldQualityFlags(
            enhanced = false,
            smoothTextures = false,
            read = { flags },
            write = { flags = it }
        ) {
            drew = true
            assertEquals(original, flags)
        }

        assertTrue(drew)
        assertEquals(original, flags)
    }

    @Test
    fun smoothTextureSettingEnablesOnlyNativeUnitAntialiasAndRestoresIt() {
        val original = WorldRenderFlagState(false, false, true, false)
        var flags = original

        WorldGraphicsEnhancements.withWorldQualityFlags(
            enhanced = false,
            smoothTextures = true,
            read = { flags },
            write = { flags = it }
        ) {
            assertFalse(flags.extraShadows)
            assertFalse(flags.fancyWater)
            assertTrue(flags.clouds)
            assertTrue(flags.antiAlias)
        }

        assertEquals(original, flags)
    }

    @Test
    fun smoothWorldTextureSamplingRestoresPaintAfterSuccessOrFailure() {
        val paint = Paint()
        assertFalse(readPaintFilterBitmap(paint))
        WorldFilteredPaints.draw(paint, configure = { copy ->
            Paint::class.java.getDeclaredField("flags").apply { isAccessible = true }.setInt(copy, 2)
        }) { filtered ->
            assertTrue(readPaintFilterBitmap(filtered))
            assertFalse(readPaintFilterBitmap(paint))
        }

        assertFailsWith<IllegalStateException> {
            WorldFilteredPaints.draw(paint, configure = { copy ->
                Paint::class.java.getDeclaredField("flags").apply { isAccessible = true }.setInt(copy, 2)
            }) { filtered ->
                assertTrue(readPaintFilterBitmap(filtered))
                error("map draw failed")
            }
        }
        assertFalse(readPaintFilterBitmap(paint))
    }

    @Test
    fun customWorldShaderUsesDesktopGlCapabilityAndDoesNotRequireAndroidNewRendererFlag() {
        assertTrue(
            DesktopWorldPostShaderEnhancements.supportsCustomShader(
                enhanced = true,
                graphicsClassName = "com.corrodinggames.rts.java.JavaCanvas",
                nativeShadersEnabled = true
            )
        )
        assertFalse(
            DesktopWorldPostShaderEnhancements.supportsCustomShader(
                enhanced = true,
                graphicsClassName = "com.corrodinggames.rts.gameFramework.render.GraphicsEngineNull",
                nativeShadersEnabled = true
            )
        )
        assertFalse(
            DesktopWorldPostShaderEnhancements.supportsCustomShader(
                enhanced = true,
                graphicsClassName = "com.corrodinggames.rts.java.JavaCanvas",
                nativeShadersEnabled = false
            )
        )
        assertFalse(
            DesktopWorldPostShaderEnhancements.supportsCustomShader(
                enhanced = false,
                graphicsClassName = "com.corrodinggames.rts.java.JavaCanvas",
                nativeShadersEnabled = true
            )
        )
    }

    @Test
    fun displacementShaderRetainsNativeInputsAndRestoresIndependently() {
        val shader = FakeShader("native displacement")
        val base = FakeShader("native base")
        val screen = shader.getUniform("screenBase")
        assertTrue(DesktopWorldPostShaderEnhancements.updateDisplacementSource(shader, true))
        assertTrue(DesktopWorldPostShaderEnhancements.updateSource(base, true))
        assertEquals(WorldPostShaderSources.DISPLACEMENT_FRAGMENT_SHADER, shader.fragmentSource)
        assertTrue(shader.fragmentSource.contains("128.0/255.0"))
        assertTrue(shader.fragmentSource.contains("rwppLightWorld(offsetScreenUV)"))
        assertTrue(shader.fragmentSource.contains("texture2D(screenBase,"))
        assertTrue(shader.fragmentSource.contains("texture2D(u_texture,v_texCoords)"))
        shader.needsReload = false
        assertTrue(DesktopWorldPostShaderEnhancements.updateDisplacementSource(shader, true))
        assertFalse(shader.needsReload)
        assertFalse(DesktopWorldPostShaderEnhancements.updateDisplacementSource(shader, false))
        assertEquals("native displacement", shader.fragmentSource)
        assertEquals(listOf(screen), shader.values.toList())
        assertEquals(DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER, base.fragmentSource)
    }

    @Test
    fun samplerUploadNoiseIsDroppedButShaderDiagnosticsAreForwarded() {
        val messages = ArrayList<String>()
        val shader = object : PaintGLShader() {
            override fun logShader(message: String) {
                messages += message
            }
        }
        repeat(20) { WorldPostSamplerLogMixin.samplerLog(shader, "Updating texture to:1896") }
        WorldPostSamplerLogMixin.samplerLog(shader, "Unknown parameter: missing")
        WorldPostSamplerLogMixin.samplerLog(shader, "Compiling shader")
        assertEquals(listOf("Unknown parameter: missing", "Compiling shader"), messages)
    }

    @Test
    fun worldShaderInstallsOnceAndRestoresVanillaSource() {
        val shader = FakeShader("#version 130\nvoid main() { gl_FragColor = vec4(1.0); }")
        val vanilla = shader.fragmentSource

        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = true)
        val enhanced = shader.fragmentSource
        assertTrue(shader.needsReload)
        assertEquals(DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER, enhanced)
        assertTrue(enhanced != vanilla)

        shader.needsReload = false
        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = true)
        assertFalse(shader.needsReload, "unchanged source should not trigger shader recompilation each frame")

        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = false)
        assertEquals(vanilla, shader.fragmentSource)
        assertTrue(shader.needsReload)
    }

    @Test
    fun shaderCompileFailureRestoresNativeShaderAndWaitsUntilToggleOff() {
        val shader = FakeShader("native shader source")
        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = true)
        assertEquals(DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER, shader.fragmentSource)

        shader.errorState = 1
        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = true)
        assertEquals("native shader source", shader.fragmentSource)
        assertEquals(0, shader.errorState)
        assertTrue(shader.needsReload)

        shader.needsReload = false
        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = true)
        assertEquals("native shader source", shader.fragmentSource)
        assertFalse(shader.needsReload, "a rejected shader should not be recompiled every frame")

        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = false)
        DesktopWorldPostShaderEnhancements.updateSource(shader, enabled = true)
        assertEquals(DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER, shader.fragmentSource)
        assertTrue(shader.needsReload)
    }

    @Test
    fun postShaderTransitionsInvalidateProgramBindingsAndRestoreNativeUniforms() {
        val shader = NativePostShader()
        val native = shader.getUniform("u_texture").apply {
            uniformLocation = 17; dirty = false; unknownParameter = true
        }
        val nativeValues = shader.values
        assertTrue(DesktopWorldPostShaderEnhancements.updateSource(shader, true))
        assertEquals(-1, native.uniformLocation)
        assertTrue(native.dirty)
        assertFalse(native.unknownParameter)
        val texture = BitmapOrTexture()
        shader.setTextureUniform("u_sceneMaterials", texture)
        shader.setFloat2Uniform("u_sceneExtent", 0.75f, 0.875f)
        shader.values.forEach { it.uniformLocation = 9; it.dirty = false; it.unknownParameter = true }
        shader.needsReload = false
        assertTrue(DesktopWorldPostShaderEnhancements.updateSource(shader, true))
        assertFalse(shader.needsReload, "normal frames must not recompile the post shader")
        assertTrue(shader.values.all { it.uniformLocation == 9 && !it.dirty })

        assertFalse(DesktopWorldPostShaderEnhancements.updateSource(shader, false))
        assertTrue(
            shader.values === nativeValues,
            "restore native uniform ownership, dropping custom sampler references"
        )
        assertEquals(listOf("u_texture"), shader.values.map { it.shaderName })
        assertEquals(-1, native.uniformLocation)
        assertTrue(native.dirty)
        assertFalse(native.unknownParameter)
        shader.needsReload = false
        assertFalse(DesktopWorldPostShaderEnhancements.updateSource(shader, false))
        assertFalse(shader.needsReload, "disabled frames must leave the native shader alone")

        native.uniformLocation = 0; native.dirty = false
        assertTrue(DesktopWorldPostShaderEnhancements.updateSource(shader, true))
        assertEquals(-1, native.uniformLocation, "native locations must not survive the next custom link")
        assertTrue(native.dirty)
    }

    @Test
    fun rejectedPostProgramDropsCustomUniformsBeforeNativeFallbackAndRetry() {
        val shader = NativePostShader()
        val native = shader.getUniform("u_texture")
        assertTrue(DesktopWorldPostShaderEnhancements.updateSource(shader, true))
        shader.setTextureUniform("u_sceneMaterials", BitmapOrTexture())
        shader.errorState = 1
        assertFalse(DesktopWorldPostShaderEnhancements.updateSource(shader, true))
        assertEquals("native post shader", shader.fragmentSource)
        assertEquals(listOf(native), shader.values.toList())
        assertTrue(native.dirty)
        assertFalse(
            DesktopWorldPostShaderEnhancements.updateSource(shader, true),
            "failed custom programs must not receive new uniforms"
        )
        DesktopWorldPostShaderEnhancements.updateSource(shader, false)
        assertTrue(DesktopWorldPostShaderEnhancements.updateSource(shader, true), "off/on must allow a clean retry")
    }

    @Test
    fun worldAndMapHooksWrapNativeWorldDrawCallsOnly() {
        assertHook(
            "com/corrodinggames/rts/game/RWGameEngine", WorldGraphicsDrawMixin::class.java,
            "drawAll", "drawWorld"
        )
        assertHook(
            "com/corrodinggames/rts/game/map/MapLayer", WorldMapTextureFilterMixin::class.java,
            "draw", "drawMapTile"
        )
        assertHook(
            "com/corrodinggames/rts/gameFramework/GameEngine", DesktopWorldPostProcessingMixin::class.java,
            "canUseShaderEffects", "enableSupportedPostProcessing"
        )
        assertHook(
            "com/corrodinggames/rts/game/RWGameEngine", DesktopWorldPostImageMixin::class.java,
            "setupPostprocessing", "installWorldPostProcessing"
        )
        assertHook(
            "com/corrodinggames/rts/java/JavaCanvas", WorldDisplacementSamplerMixin::class.java,
            "updateShaderUniforms", "bindSamplers"
        )
        assertHook(
            "com/corrodinggames/rts/java/JavaCanvas", WorldPostSamplerLogMixin::class.java,
            "updateShaderUniforms", "samplerLog"
        )
    }

    private fun assertHook(target: String, mixin: Class<*>, methodName: String, handlerName: String) {
        val classLoader = javaClass.classLoader
        val source = requireNotNull(classLoader.getResourceAsStream("$target.class")) {
            "native renderer target $target must be present in the UI regression classpath"
        }.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(mixin)
            val transformed = AsmProcessor().transform(target, source, classLoader)
            val node = ClassNode()
            ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES)
            val injected = node.methods.single { it.name == methodName }.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
                .count { it.owner == mixin.name.replace('.', '/') && it.name == handlerName }
            assertTrue(injected > 0, "$target.$methodName must call $handlerName")
        } finally {
            AsmRegistry.clear()
        }
    }

    private class FakeShader(source: String) : PaintGLShader() {
        init {
            fragmentSource = source; needsReload = false; errorState = 0
        }
    }

    private class NativePostShader : PaintGLShader() {
        init {
            errorState = 0; fragmentSource = "native post shader"
        }
    }

    private fun readPaintFilterBitmap(paint: Paint): Boolean =
        Paint::class.java.getDeclaredField("flags").apply { isAccessible = true }.getInt(paint) and 2 != 0
}
