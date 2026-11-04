package io.github.rwpp.api.config

import kotlinx.serialization.Serializable
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.*

@Serializable
data class PersistenceFixture(var enabled: Boolean = false, var rate: Int = 60, var names: List<String> = emptyList()) :
    Config

class ConfigPersistenceTest {
    @Test
    fun savedValuesRestoreIntoExistingSingletonAndTolerateNewerFields() {
        val previous = System.getProperty("user.dir")
        val directory = Files.createTempDirectory("rwpp-config-roundtrip")
        try {
            System.setProperty("user.dir", directory.toString())
            val writer = ConfigIOImpl()
            ConfigSerializers.register(PersistenceFixture::class.java, PersistenceFixture.serializer())
            writer.saveConfig(PersistenceFixture(true, 144, listOf("official", "custom")))
            val file = directory.resolve("${PersistenceFixture::class.qualifiedName}.toml")
            Files.writeString(file, "\nfutureVersionOption = true\n", java.nio.file.StandardOpenOption.APPEND)
            val target = PersistenceFixture()
            target.copyConfigFrom(ConfigIOImpl().readConfig(PersistenceFixture::class)!!)
            assertTrue(target.enabled); assertEquals(144, target.rate)
            assertEquals(listOf("official", "custom"), target.names)
            val app = koinApplication { modules(module { single<Config> { target } }) }
            val reader = object : ConfigIO by writer {
                override fun getKoin() = app.koin
                override fun readAllConfig() = super<ConfigIO>.readAllConfig()
            }
            try {
                target.enabled = false; target.rate = 30; target.names = emptyList()
                reader.readAllConfig()
                assertTrue(target.enabled); assertEquals(144, target.rate)
                assertEquals(listOf("official", "custom"), target.names)
            } finally {
                app.close()
            }
        } finally {
            System.setProperty("user.dir", previous)
        }
    }

    @Test
    fun aDecodeFailureNeverDeletesUserConfiguration() {
        val target = PersistenceFixture()
        val app = koinApplication { modules(module { single<Config> { target } }) }
        var deleted = false
        val io = object : ConfigIO {
            override fun getKoin() = app.koin
            override fun <T : Config> readConfig(clazz: KClass<T>): T? = error("invalid user TOML")
            override fun <T : Config> deleteConfig(clazz: KClass<T>) {
                deleted = true
            }

            override fun saveConfig(config: Config) = Unit
            override fun saveSingleConfig(group: String, key: String, value: Any?) = Unit
            override fun readSingleConfig(group: String, key: String): String? = null
            override fun <T> getGameConfig(name: String): T = error("not used")
            override fun setGameConfig(name: String, value: Any?) = Unit
        }
        try {
            io.readAllConfig(); assertFalse(deleted)
            assertFailsWith<IllegalStateException> { io.saveAllConfig() }
        } finally {
            app.close(); ConfigLoadState.loaded(target)
        }
    }
}
