package io.github.rwpp.ui

import io.github.rwpp.mixin.building.NativePlacementGridMixin
import io.github.rwpp.mixin.building.PlacementGridFrameMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.AsmScanner
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class NativePlacementGridMixinTest {
    @Test
    fun archiveScanInjectsIntoTheNativePlacementGridDraw() {
        AsmRegistry.clear()
        val archive = Files.createTempFile("rwpp-native-placement-grid", ".jar")
        try {
            val mixinPath = "${NativePlacementGridMixin::class.java.name.replace('.', '/')}.class"
            JarOutputStream(Files.newOutputStream(archive)).use { jar ->
                jar.putNextEntry(JarEntry(mixinPath))
                javaClass.classLoader.getResourceAsStream(mixinPath)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
            // RWML scans the extension archive, so exercise that path rather than
            // registering the mixin directly.
            val scan = AsmScanner.scanJarWithResult(archive.toFile(), "")
            assertTrue(
                NativePlacementGridMixin::class.java.name in scan.registeredClasses,
                "archive scanning should discover NativePlacementGridMixin; registered=${scan.registeredClasses}",
            )
            assertTrue(scan.failures.isEmpty(), "archive mixin scan must be clean: ${scan.failures}")

            val target = "com/corrodinggames/rts/game/map/Map"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use {
                ClassReader(it).accept(node, 0)
            }
            assertTrue(AsmProcessor().applyAsms(target, node))
            val method = node.methods.single { it.name == "drawBuildingPlacement" }
            val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(
                calls.any {
                    it.owner == "io/github/rwpp/mixin/building/NativePlacementGridMixin" && it.name == "capture"
                },
                "the native green tile grid must be sampled before its own drawing",
            )
            assertTrue(
                calls.any { it.name == "drawRectAlt" },
                "the native green squares keep drawing underneath the Aero outline",
            )
        } finally {
            AsmRegistry.clear()
            Files.deleteIfExists(archive)
        }
    }

    @Test
    fun archiveScanInjectsTheFrameBoundaryIntoInterfaceEngine() {
        AsmRegistry.clear()
        val archive = Files.createTempFile("rwpp-placement-frame-mixin", ".jar")
        try {
            val mixinPath = "${PlacementGridFrameMixin::class.java.name.replace('.', '/')}.class"
            JarOutputStream(Files.newOutputStream(archive)).use { jar ->
                jar.putNextEntry(JarEntry(mixinPath))
                javaClass.classLoader.getResourceAsStream(mixinPath)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
            val scan = AsmScanner.scanJarWithResult(archive.toFile(), "")
            assertTrue(PlacementGridFrameMixin::class.java.name in scan.registeredClasses)
            assertTrue(scan.failures.isEmpty(), "archive mixin scan must be clean: ${scan.failures}")

            val target = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use {
                ClassReader(it).accept(node, 0)
            }
            assertTrue(AsmProcessor().applyAsms(target, node))
            val method = node.methods.single { it.name == "update" && it.desc == "(F)V" }
            assertTrue(
                method.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                    it.owner == "io/github/rwpp/mixin/building/PlacementGridFrameMixin" &&
                            it.name == "beginFrame"
                },
                "each native frame must publish and clear the placement squares",
            )
        } finally {
            AsmRegistry.clear()
            Files.deleteIfExists(archive)
        }
    }
}
