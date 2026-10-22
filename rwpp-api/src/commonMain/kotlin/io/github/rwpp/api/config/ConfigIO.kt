/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.config

import org.koin.core.component.KoinComponent
import io.github.rwpp.api.logging.logger
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.declaredMemberProperties

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
        getKoin().getAll<Config>().forEach(::saveConfig)
    }

    fun readAllConfig() {
        getKoin().getAll<Config>().forEach { config ->
            runCatching {
                readConfig(config::class)?.let(config::copyConfigFrom)
            }.onFailure {
                logger().error("Failed to read configuration ${config::class.qualifiedName}", it)
                deleteConfig(config::class)
            }
        }
    }
}

private fun <T : Config> T.copyConfigFrom(source: T) {
    source::class.declaredMemberProperties
        .filterIsInstance<KMutableProperty<*>>()
        .forEach { property ->
            property.setter.call(this, property.getter.call(source))
        }
}
