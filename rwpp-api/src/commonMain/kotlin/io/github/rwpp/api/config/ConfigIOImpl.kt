/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
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
    private val gameSettingsFields = ConcurrentHashMap<String, Field>()

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun saveConfig(config: Config) {
        ConfigStorage.saveConfig(config::class.qualifiedName.orEmpty(), Toml.encodeToString(
            config::class.serializer() as KSerializer<Any>,
            config,
        ))
    }

    @OptIn(InternalSerializationApi::class)
    override fun <T : Config> readConfig(clazz: KClass<T>): T? {
        val source = ConfigStorage.readConfig(clazz.qualifiedName.orEmpty()) ?: return null
        if (source.isBlank()) return null
        return Toml.decodeFromString(clazz.serializer(), source)
    }

    override fun <T : Config> deleteConfig(clazz: KClass<T>) {
        ConfigStorage.deleteConfig(clazz.qualifiedName.orEmpty())
    }

    override fun saveSingleConfig(group: String, key: String, value: Any?) =
        ConfigStorage.saveSingleConfig(group, key, value?.toString().orEmpty())

    override fun readSingleConfig(group: String, key: String): String? =
        ConfigStorage.readSingleConfig(group, key)

    @Suppress("UNCHECKED_CAST")
    override fun <T> getGameConfig(name: String): T = gameSettingsField(name).get(GameEngine.getInstance().settings) as T

    override fun setGameConfig(name: String, value: Any?) {
        gameSettingsField(name).set(GameEngine.getInstance().settings, value)
    }

    override fun saveAllConfig() {
        super.saveAllConfig()
        GameEngine.getInstance().settings.save()
    }

    private fun gameSettingsField(name: String): Field =
        gameSettingsFields.getOrPut(name) { SettingsEngine::class.java.getDeclaredField(name).apply { isAccessible = true } }
}
