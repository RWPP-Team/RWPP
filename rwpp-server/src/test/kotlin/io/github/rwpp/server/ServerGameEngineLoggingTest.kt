package io.github.rwpp.server

import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerGameEngineLoggingTest {
    @Test
    fun dedicatedServerLogHookTransformsTheNativeEngineLoggerOnly() {
        val target = "com/corrodinggames/rts/gameFramework/GameEngine"
        val transformedBytes = transformGameEngine()
        assertTrue(transformedBytes.isNotEmpty(), "Transformed GameEngine class bytes must be written")

        val node = ClassNode()
        ClassReader(transformedBytes).accept(node, ClassReader.EXPAND_FRAMES)
        val logInternal = node.methods.single { it.name == "logInternal" && it.desc == "(Ljava/lang/String;)V" }
        assertTrue(
            logInternal.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                it.owner == "io/github/rwpp/server/ServerGameEngineLogMixin" &&
                        it.name == "suppressDedicatedServerEngineLog"
            },
            "The dedicated-only filter belongs at GameEngine.logInternal"
        )
        val warning = node.methods.single { it.name == "logWarning" && it.desc == "(Ljava/lang/String;)V" }
        val warningHooks = warning.instructions.toArray().filterIsInstance<MethodInsnNode>()
            .filter { it.owner == "io/github/rwpp/server/ServerGameEngineLogMixin" }
            .map { it.name }.toSet()
        assertTrue("enterWarningLog" in warningHooks)
        assertTrue("exitWarningLog" in warningHooks)
    }

    @Test
    fun transformedNativeLogMethodSuppressesOutputWhenDedicatedServerFlagIsSet() {
        val target = "com.corrodinggames.rts.gameFramework.GameEngine"
        val targetBytes = transformGameEngine()
        val mixinName = "io.github.rwpp.server.ServerGameEngineLogMixin"
        val mixinBytes = javaClass.classLoader.getResourceAsStream("${mixinName.replace('.', '/')}.class")!!
            .use { it.readBytes() }
        val loader = TransformedClassLoader(
            javaClass.classLoader,
            mapOf(target to targetBytes, mixinName to mixinBytes),
        )
        val output = ByteArrayOutputStream()
        val previousOutput = System.out
        val capture = PrintStream(output, true, StandardCharsets.UTF_8)
        try {
            System.setOut(capture)
            val gameEngine = Class.forName(target, true, loader)
            gameEngine.getField("isDedicatedServerPlatform").setBoolean(null, true)
            output.reset()
            gameEngine.getMethod("log", String::class.java).invoke(null, "FILTERED_GAMEENGINE_LOG_SENTINEL")
            assertFalse(
                output.toString(StandardCharsets.UTF_8).contains("FILTERED_GAMEENGINE_LOG_SENTINEL"),
                "The injected cancellable HEAD must return before android.util.Log.d",
            )

            gameEngine.getField("isDedicatedServerPlatform").setBoolean(null, false)
            output.reset()
            gameEngine.getMethod("log", String::class.java).invoke(null, "VISIBLE_GAMEENGINE_LOG_SENTINEL")
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("VISIBLE_GAMEENGINE_LOG_SENTINEL"))
        } finally {
            System.setOut(previousOutput)
            capture.close()
        }
    }

    @Test
    fun transformedAndroidLogFiltersDirectRoutineEngineCallsAndKeepsDiagnostics() {
        val gameEngineName = "com.corrodinggames.rts.gameFramework.GameEngine"
        val logName = "android.util.Log"
        val gameEngineBytes = transformGameEngine()
        val logBytes = transformAndroidLog()
        val definitions = mapOf(
            gameEngineName to gameEngineBytes,
            logName to logBytes,
            "android.util.Log\$1" to classBytes("android/util/Log\$1"),
            "android.util.Log\$TerribleFailureHandler" to classBytes("android/util/Log\$TerribleFailureHandler"),
            "io.github.rwpp.server.ServerGameEngineLogMixin" to mixinBytes("io.github.rwpp.server.ServerGameEngineLogMixin"),
            "io.github.rwpp.server.ServerAndroidLogMixin" to mixinBytes("io.github.rwpp.server.ServerAndroidLogMixin"),
        )
        val loader = TransformedClassLoader(javaClass.classLoader, definitions)
        val output = ByteArrayOutputStream()
        val previousOutput = System.out
        val capture = PrintStream(output, true, StandardCharsets.UTF_8)
        try {
            System.setOut(capture)
            val gameEngine = Class.forName(gameEngineName, true, loader)
            val nativeLog = Class.forName(logName, true, loader)
            gameEngine.getField("isDedicatedServerPlatform").setBoolean(null, true)
            output.reset()

            nativeLog.getMethod("d", String::class.java, String::class.java)
                .invoke(null, "RustedWarfare", "FILTERED_DIRECT_DEBUG_SENTINEL")
            nativeLog.getMethod("v", String::class.java, String::class.java)
                .invoke(null, "RustedWarfare", "FILTERED_DIRECT_VERBOSE_SENTINEL")
            nativeLog.getMethod("e", String::class.java, String::class.java)
                .invoke(null, "RustedWarfare", "GameEngine:GameEngine()")
            val routineEngineErrors = listOf(
                "--- ----------------- ----",
                "--- GameEngine:init() ----",
                "packageName:dedicatedServer",
                "gameThread already null",
                "MapLayer create: Ground",
                "StateEngine: get(maps/skirmish/test.tmx)=null  (key=maps/skirmish/test.tmx)",
            )
            routineEngineErrors.forEach { message ->
                nativeLog.getMethod("e", String::class.java, String::class.java)
                    .invoke(null, "RustedWarfare", message)
            }
            gameEngine.getMethod("logWarning", String::class.java)
                .invoke(null, "selectMenuOption: inGameActivity==null")
            val filteredOutput = output.toString(StandardCharsets.UTF_8)
            assertFalse(filteredOutput.contains("FILTERED_DIRECT_DEBUG_SENTINEL"))
            assertFalse(filteredOutput.contains("FILTERED_DIRECT_VERBOSE_SENTINEL"))
            (routineEngineErrors + listOf("GameEngine:GameEngine()", "selectMenuOption: inGameActivity==null"))
                .forEach { message ->
                    assertFalse(
                        filteredOutput.contains(message),
                        "Unexpected diagnostic output: $message"
                    )
                }

            nativeLog.getMethod("e", String::class.java, String::class.java)
                .invoke(null, "RustedWarfare", "VISIBLE_REAL_ERROR_SENTINEL")
            nativeLog.getMethod("e", String::class.java, String::class.java, Throwable::class.java)
                .invoke(null, "RustedWarfare", "VISIBLE_THROWABLE_ERROR_SENTINEL", IllegalStateException("diagnostic"))
            nativeLog.getMethod("w", String::class.java, String::class.java)
                .invoke(null, "RustedWarfare", "VISIBLE_WARNING_SENTINEL")
            nativeLog.getMethod("d", String::class.java, String::class.java)
                .invoke(null, "RwppServer", "VISIBLE_APPLICATION_LOG_SENTINEL")
            gameEngine.getMethod("logWarning", String::class.java)
                .invoke(null, "VISIBLE_WARNING_THROUGH_GAMEENGINE_SENTINEL")
            val visibleOutput = output.toString(StandardCharsets.UTF_8)
            assertTrue(visibleOutput.contains("VISIBLE_REAL_ERROR_SENTINEL"))
            assertTrue(visibleOutput.contains("VISIBLE_THROWABLE_ERROR_SENTINEL"))
            assertTrue(visibleOutput.contains("VISIBLE_WARNING_SENTINEL"))
            assertTrue(visibleOutput.contains("VISIBLE_APPLICATION_LOG_SENTINEL"))
            assertTrue(visibleOutput.contains("VISIBLE_WARNING_THROUGH_GAMEENGINE_SENTINEL"))

            gameEngine.getField("isDedicatedServerPlatform").setBoolean(null, false)
            nativeLog.getMethod("d", String::class.java, String::class.java)
                .invoke(null, "RustedWarfare", "VISIBLE_NON_DEDICATED_DEBUG_SENTINEL")
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("VISIBLE_NON_DEDICATED_DEBUG_SENTINEL"))
        } finally {
            System.setOut(previousOutput)
            capture.close()
        }
    }

    @Test
    fun plainEngineLogsAreDroppedOnlyOnDedicatedServersAndNestedDiagnosticsPassThrough() {
        assertTrue(ServerGameEngineLogMixin.shouldSuppress(dedicatedServer = true))
        assertFalse(ServerGameEngineLogMixin.shouldSuppress(dedicatedServer = false))
        listOf(
            "GameEngine:GameEngine()",
            "--- ----------------- ----",
            "--- GameEngine:init() ----",
            "gameThread already null",
            "packageName:dedicatedServer",
            "MapLayer create: Ground",
            "StateEngine: get(maps/skirmish/test.tmx)=null",
        ).forEach { message ->
            assertTrue(
                ServerGameEngineLogMixin.shouldSuppressDirectAndroidLog(
                    dedicatedServer = true,
                    priority = 6,
                    tag = "RustedWarfare",
                    message = message,
                ), message
            )
        }
        assertFalse(
            ServerGameEngineLogMixin.shouldSuppressDirectAndroidLog(
                dedicatedServer = true,
                priority = 6,
                tag = "RustedWarfare",
                message = "Unexpected error: disk write failed",
            ),
        )
        assertFalse(
            ServerGameEngineLogMixin.shouldSuppressDirectAndroidLog(
                dedicatedServer = true,
                priority = 5,
                tag = "RustedWarfare",
                message = "warning",
            ),
        )
        assertTrue(
            ServerGameEngineLogMixin.shouldSuppressDedicatedWarning(
                dedicatedServer = true,
                message = "selectMenuOption: inGameActivity==null",
            ),
        )
        assertTrue(
            ServerGameEngineLogMixin.shouldSuppressDedicatedWarning(
                dedicatedServer = true,
                message = "\u001B[33mselectMenuOption: inGameActivity==null\u001B[0m",
            ),
        )
        assertFalse(
            ServerGameEngineLogMixin.shouldSuppressDedicatedWarning(
                dedicatedServer = true,
                message = "another warning",
            ),
        )
        assertFalse(
            ServerGameEngineLogMixin.shouldSuppressDedicatedWarning(
                dedicatedServer = false,
                message = "selectMenuOption: inGameActivity==null",
            ),
        )

        ServerGameEngineLogMixin.beginPreservedLog()
        try {
            assertFalse(ServerGameEngineLogMixin.shouldSuppress(dedicatedServer = true))
            assertFalse(
                ServerGameEngineLogMixin.shouldSuppressDirectAndroidLog(
                    dedicatedServer = true,
                    priority = 3,
                    tag = "RustedWarfare",
                    message = "GameEngine warning forwarded to debug logger",
                ),
            )
            ServerGameEngineLogMixin.beginPreservedLog()
            ServerGameEngineLogMixin.endPreservedLog()
            assertFalse(ServerGameEngineLogMixin.shouldSuppress(dedicatedServer = true))
        } finally {
            ServerGameEngineLogMixin.endPreservedLog()
        }
        assertTrue(ServerGameEngineLogMixin.shouldSuppress(dedicatedServer = true))
    }

    private fun transformGameEngine(): ByteArray {
        return transform("com/corrodinggames/rts/gameFramework/GameEngine", ServerGameEngineLogMixin::class.java)
    }

    private fun transformAndroidLog(): ByteArray =
        transform("android/util/Log", ServerAndroidLogMixin::class.java)

    private fun transform(target: String, vararg mixins: Class<*>): ByteArray {
        AsmRegistry.clear()
        try {
            mixins.forEach(AsmRegistry::register)
            val originalBytes = javaClass.classLoader.getResourceAsStream("$target.class")!!.use { it.readBytes() }
            return AsmProcessor().transform(target, originalBytes, javaClass.classLoader)
        } finally {
            AsmRegistry.clear()
        }
    }

    private fun mixinBytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("${name.replace('.', '/')}.class")!!.use { it.readBytes() }

    private fun classBytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }

    private class TransformedClassLoader(
        parent: ClassLoader,
        private val definitions: Map<String, ByteArray>,
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name)
            if (loaded == null) {
                val bytes = definitions[name]
                loaded = if (bytes == null) super.loadClass(name, false) else defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
