/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

@file:JvmName("anyKt")

/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 PolyForm Shield License 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the PolyForm Shield License that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.utils

import java.lang.reflect.Modifier
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.declaredMemberProperties

fun <T : Any> T.setPropertyFromObject(obj: T) {
    obj::class.declaredMemberProperties.forEach { member ->
        if (member is KMutableProperty<*>) {
            member.setter.call(this, member.getter.call(obj))
        }
    }
}

fun <T : Any> T.printObject(targetClass: Class<*> = this::class.java) {
    println("class: " + targetClass.canonicalName + ": $this" + " {")
    targetClass.declaredFields.forEach { field ->
        field.isAccessible = true
        val static = Modifier.isStatic(field.modifiers)
        val v = if (static) field.get(null) else field.get(this)
        println(
            (if (static) "static: " else "") + field.name + ": " + when (v) {
                is Array<*> -> "[size:${v.size}{${v.joinToString(", ")}}]"
                is List<*> -> "[size:${v.size}{${v.joinToString(", ")}}]"
                else -> v
            },
        )
    }
    println("}")
}