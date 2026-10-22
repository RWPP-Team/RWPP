/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.util

import kotlin.reflect.KClass

/** Reflection helpers used to reach engine members that the mappings do not expose by name. */
object Reflect {

    @Suppress("UNCHECKED_CAST")
    fun <T> get(any: Any, field: String): T? = any::class.java.getDeclaredField(field).apply {
        isAccessible = true
    }.get(any) as T?

    fun set(any: Any, field: String, value: Any?) {
        any::class.java.getDeclaredField(field).apply {
            isAccessible = true
        }.set(any, value)
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T, K> reifiedGet(any: T?, field: String): K? = T::class.java.getDeclaredField(field).apply {
        isAccessible = true
    }.get(any) as K?

    inline fun <reified T> reifiedSet(any: T?, field: String, value: Any?) {
        T::class.java.getDeclaredField(field).apply {
            isAccessible = true
        }.set(any, value)
    }

    inline fun <reified T : Any> callVoid(any: T?, method: String, classes: List<KClass<*>>? = null, args: List<Any?>) {
        call<T, Any>(any, method, classes, args)
    }

    inline fun <reified T, K : Any> callNotNull(
        any: T?,
        method: String,
        classes: List<KClass<*>>? = null,
        args: List<Any?>,
    ): K = call<T, K>(any, method, classes, args)!!

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T, K : Any> call(
        any: T?,
        method: String,
        classes: List<KClass<*>>? = null,
        args: List<Any?>,
    ): K? = (
        classes?.let {
            T::class.java.getDeclaredMethod(
                method,
                *classes.map { it.java }.toTypedArray(),
            )
        } ?: T::class.java.declaredMethods.first { it.name == method }
        ).apply {
        isAccessible = true
    }.invoke(any, *args.toTypedArray()) as K?
}
