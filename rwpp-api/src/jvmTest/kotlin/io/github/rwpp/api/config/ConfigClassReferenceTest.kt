package io.github.rwpp.api.config

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.test.*

/** Models the Android host initializing stdlib Reflection before an extension supplies kotlin-reflect. */
class ConfigClassReferenceTest {
    @Test
    fun restoringMutableFieldsDoesNotRequireFullKotlinReflectionInTheHost() {
        val stdlib = Class.forName("kotlin.jvm.internal.Reflection").protectionDomain.codeSource.location
        val reflect = Class.forName("kotlin.reflect.jvm.internal.KClassImpl").protectionDomain.codeSource.location
        val api = Config::class.java.protectionDomain.codeSource.location
        val fixtures = PersistenceFixture::class.java.protectionDomain.codeSource.location
        val serialization = kotlinx.serialization.KSerializer::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(stdlib), ClassLoader.getPlatformClassLoader()).use { host ->
            val reflection = host.loadClass("kotlin.jvm.internal.Reflection")
            val kclass = host.loadClass("kotlin.reflect.KClass")
            val classRef =
                reflection.getMethod("getOrCreateKotlinClass", Class::class.java).invoke(null, String::class.java)
            assertEquals("kotlin.jvm.internal.ClassReference", classRef.javaClass.name)
            URLClassLoader(arrayOf(reflect, api, fixtures, serialization), host).use { extension ->
                val properties =
                    extension.loadClass("kotlin.reflect.full.KClasses").getMethod("getDeclaredMemberProperties", kclass)
                val unsupported = assertFailsWith<InvocationTargetException> { properties.invoke(null, classRef) }
                assertTrue(
                    unsupported.cause is ClassCastException,
                    "The fixture must reproduce the Android reflection mismatch"
                )
                val fixture = extension.loadClass(PersistenceFixture::class.java.name)
                val generated = fixture.getField("Companion").get(null)
                val serializer = generated.javaClass.getMethod("serializer").invoke(generated)
                val registry = extension.loadClass(ConfigSerializers::class.java.name)
                val registryInstance = registry.getField("INSTANCE").get(null)
                registry.getMethod(
                    "register",
                    Class::class.java,
                    extension.loadClass("kotlinx.serialization.KSerializer")
                )
                    .invoke(registryInstance, fixture, serializer)
                assertSame(
                    serializer,
                    registry.methods.single { it.name.startsWith("find") }.invoke(registryInstance, fixture)
                )
                val original = fixture.getConstructor().newInstance()
                val restored = fixture.getConstructor().newInstance()
                fixture.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(restored, true)
                fixture.getMethod("setRate", Int::class.javaPrimitiveType).invoke(restored, 144)
                val marker = extension.loadClass(Config::class.java.name)
                extension.loadClass("io.github.rwpp.api.config.ConfigIOKt").getMethod("copyConfigFrom", marker, marker)
                    .invoke(null, original, restored)
                assertEquals(true, fixture.getMethod("getEnabled").invoke(original))
                assertEquals(144, fixture.getMethod("getRate").invoke(original))
            }
        }
    }
}
