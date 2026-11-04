package io.github.rwpp.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fresh process exercises the real RWML loader, dependency identity and resource ownership. */
class ServerExtensionHostTest {
    private val classpath = System.getProperty("rwpp.server.test.classpath")

    @Test
    fun extensionRunsInsteadOfVanillaAndClosesOwnedResources() {
        val root = fixture()
        val result = launch(root)
        assertEquals(0, result.first, result.second)
        assertTrue("CUSTOM_SERVER_OK [--no-console, ignored]" in result.second, result.second)
        assertFalse("Listening on port" in result.second, result.second)
        assertEquals("closed", Files.readString(root.resolve("data/fixture.mode/closed")))
        assertEquals("drained", Files.readString(root.resolve("data/fixture.mode/dispatched")))
    }

    @Test
    fun failedExtensionNeverFallsBackToVanilla() {
        val root = fixture(fail = true)
        val result = launch(root)
        assertTrue(result.first != 0, result.second)
        assertTrue("Server extensions failed to load" in result.second, result.second)
        assertFalse("CUSTOM_SERVER_OK" in result.second, result.second)
        assertTrue(Files.exists(root.resolve("data/fixture.mode/closed")))
    }

    @Test
    fun twoSimulationProvidersFailBeforeEitherRuns() {
        val root = fixture()
        val jar = root.resolve("extensions/mode.jar")
        java.util.jar.JarFile(jar.toFile()).use { source ->
            JarOutputStream(Files.newOutputStream(root.resolve("extensions/second.jar"))).use { out ->
                source.entries().asSequence().forEach { entry ->
                    out.putNextEntry(JarEntry(entry.name))
                    val bytes = source.getInputStream(entry).readAllBytes()
                    out.write(
                        if (entry.name == "META-INF/extension.properties") {
                            String(bytes).replace("fixture.mode", "fixture.second").toByteArray()
                        } else bytes
                    )
                    out.closeEntry()
                }
            }
        }
        val result = launch(root)
        assertTrue(result.first != 0, result.second)
        assertTrue("Only one extension" in result.second, result.second)
        assertFalse("CUSTOM_SERVER_OK" in result.second, result.second)
    }

    @Test
    fun clientArchiveIsRejectedBeforeItsEntryPointRuns() {
        val root = fixture(tags = "client")
        val result = launch(root)
        assertTrue(result.first != 0, result.second)
        assertTrue("Client/UI extension cannot be loaded" in result.second, result.second)
        assertFalse(Files.exists(root.resolve("data/fixture.mode/closed")))
    }

    private fun fixture(fail: Boolean = false, tags: String = "server"): Path {
        val root = Files.createTempDirectory("rwpp-server-extension-test-")
        val classes = Files.createDirectory(root.resolve("classes"))
        val source = root.resolve("FixtureMode.java")
        Files.writeString(
            source, """
            import io.github.rwml.api.ExtensionContext;
            import io.github.rwpp.server.ServerExtension;
            import io.github.rwpp.server.ServerRuntimeContext;
            import java.nio.file.Files;
            import io.github.rwpp.api.coroutines.GameMainDispatcher;
            import kotlin.coroutines.EmptyCoroutineContext;
            public class FixtureMode implements ServerExtension {
                public void onLoad(ExtensionContext context) throws Exception {
                    context.own(() -> Files.writeString(context.dataDirectory().resolve("closed"), "closed"));
                    GameMainDispatcher.INSTANCE.dispatch(EmptyCoroutineContext.INSTANCE, () -> {
                        try { Files.writeString(context.dataDirectory().resolve("dispatched"), "drained"); }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    });
                    if ($fail) throw new IllegalStateException("fixture failure");
                }
                public void runServer(ServerRuntimeContext context) {
                    context.pump();
                    System.out.println("CUSTOM_SERVER_OK " + context.getArguments());
                }
                public void requestStop() { }
            }
        """.trimIndent()
        )
        val code = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-cp", classpath, "-d", classes.toString(), source.toString()
        )
        assertEquals(0, code)
        val extensions = Files.createDirectory(root.resolve("extensions"))
        JarOutputStream(Files.newOutputStream(extensions.resolve("mode.jar"))).use { out ->
            out.putNextEntry(JarEntry("META-INF/extension.properties"))
            out.write(
                """
                id=fixture.mode
                name=Fixture
                version=1
                mainClass=FixtureMode
                dependsOn=io.github.rwpp.server
                tags=$tags
            """.trimIndent().toByteArray()
            )
            out.closeEntry()
            out.putNextEntry(JarEntry("FixtureMode.class"))
            out.write(Files.readAllBytes(classes.resolve("FixtureMode.class")))
            out.closeEntry()
        }
        return root
    }

    private fun launch(root: Path): Pair<Int, String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-Djdk.attach.allowAttachSelf=true", "-cp", classpath,
            "io.github.rwpp.server.Main", "--no-console", "--", "ignored"
        )
            .directory(root.toFile()).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Server extension fixture timed out")
        }
        return process.exitValue() to output.get(5, TimeUnit.SECONDS)
    }
}
