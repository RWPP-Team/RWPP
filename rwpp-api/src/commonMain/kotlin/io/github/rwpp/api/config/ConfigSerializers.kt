/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.config

import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap

/** Explicit serializers survive a host that initialized Kotlin's ClassReference before extensions. */
object ConfigSerializers {
    private val serializers = ConcurrentHashMap<Class<*>, KSerializer<*>>()

    fun <T : Config> register(type: Class<T>, serializer: KSerializer<T>) {
        serializers[type] = serializer
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Config> find(type: Class<T>): KSerializer<T>? = serializers[type] as KSerializer<T>?
}
