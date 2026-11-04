/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.config

import io.github.rwpp.api.logging.logger
import org.koin.core.component.KoinComponent
import java.lang.reflect.Modifier
import java.util.*
import kotlin.reflect.KClass

/** Reads and writes built-in and game configuration through the RWML API extension. */
interface ConfigIO : KoinComponent {
    fun saveConfig(config: Config)
    fun <T : Config> readConfig(clazz: KClass<T>): T?
    fun <T : Config> deleteConfig(clazz: KClass<T>)
    fun saveSingleConfig(group: String, key: String, value: Any?)
    fun readSingleConfig(group: String, key: String): String?
    fun <T> getGameConfig(name: String): T
    fun setGameConfig(name: String, value: Any?)

    fun saveAllConfig() {
        val configurations = getKoin().getAll<Config>()
        configurations.forEach(ConfigLoadState::checkWritable)
        configurations.forEach(::saveConfig)
    }

    fun readAllConfig() {
        getKoin().getAll<Config>().forEach { config ->
            runCatching {
                readConfig(config::class)?.let(config::copyConfigFrom)
                ConfigLoadState.loaded(config)
            }.onFailure {
                ConfigLoadState.failed(config, it)
                logger().error("Failed to read configuration ${config::class.qualifiedName}", it)
                System.err.println("RWPP configuration restore failed: ${config.javaClass.name}")
                it.printStackTrace()
            }
        }
    }
}

internal fun <T : Config> T.copyConfigFrom(source: T) {
    require(javaClass == source.javaClass)
    // Persisted objects are JVM data classes on both hosts. Copy backing fields without depending
    // on a platform's kotlin-reflect callable adapters; keep the singleton injected into services.
    for (field in javaClass.declaredFields) {
        if (Modifier.isStatic(field.modifiers) || Modifier.isFinal(field.modifiers) || field.isSynthetic) continue
        field.isAccessible = true
        field.set(this, field.get(source))
    }
}

internal object ConfigLoadState {
    private val failures = IdentityHashMap<Config, Throwable>()
    @Synchronized
    fun loaded(config: Config) {
        failures.remove(config)
    }

    @Synchronized
    fun failed(config: Config, cause: Throwable) {
        failures[config] = cause
    }

    @Synchronized
    fun checkWritable(config: Config) {
        failures[config]?.let {
            throw IllegalStateException(
                "Configuration ${config.javaClass.name} could not be restored; original data was preserved",
                it
            )
        }
    }
}
