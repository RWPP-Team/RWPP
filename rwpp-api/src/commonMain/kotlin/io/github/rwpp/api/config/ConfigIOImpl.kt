/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.config

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.peanuuutz.tomlkt.Toml
import org.koin.core.annotation.Single
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** Shared configuration behavior. Android and desktop only supply their persistent storage backend. */
@Single(binds = [ConfigIO::class])
class ConfigIOImpl : ConfigIO {
    override fun getKoin() = io.github.rwpp.api.appKoin
    private val format = Toml { ignoreUnknownKeys = true }
    private val gameSettingsFields = ConcurrentHashMap<String, Field>()

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun saveConfig(config: Config) {
        ConfigLoadState.checkWritable(config)
        ConfigStorage.saveConfig(
            config::class.qualifiedName.orEmpty(), format.encodeToString(
                (ConfigSerializers.find(config.javaClass) ?: config::class.serializer()) as KSerializer<Any>,
                config,
            )
        )
    }

    @OptIn(InternalSerializationApi::class)
    override fun <T : Config> readConfig(clazz: KClass<T>): T? {
        val source = ConfigStorage.readConfig(clazz.qualifiedName.orEmpty()) ?: return null
        if (source.isBlank()) return null
        return format.decodeFromString(ConfigSerializers.find(clazz.java) ?: clazz.serializer(), source)
    }

    override fun <T : Config> deleteConfig(clazz: KClass<T>) {
        ConfigStorage.deleteConfig(clazz.qualifiedName.orEmpty())
    }

    override fun saveSingleConfig(group: String, key: String, value: Any?) =
        ConfigStorage.saveSingleConfig(group, key, value?.toString().orEmpty())

    override fun readSingleConfig(group: String, key: String): String? =
        ConfigStorage.readSingleConfig(group, key)

    @Suppress("UNCHECKED_CAST")
    override fun <T> getGameConfig(name: String): T =
        gameSettingsField(name).get(GameEngine.getInstance().settings) as T

    override fun setGameConfig(name: String, value: Any?) {
        gameSettingsField(name).set(GameEngine.getInstance().settings, value)
    }

    override fun saveAllConfig() {
        val client = runCatching { super.saveAllConfig() }
        val native = runCatching { check(GameEngine.getInstance().settings.save()) { "Native settings save failed" } }
        client.exceptionOrNull()?.let { failure ->
            native.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        native.getOrThrow()
    }

    private fun gameSettingsField(name: String): Field =
        gameSettingsFields.getOrPut(name) {
            SettingsEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
}
