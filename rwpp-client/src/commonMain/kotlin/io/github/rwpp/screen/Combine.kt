/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koin.core.annotation.KoinInternalApi
import kotlin.reflect.KClass

/**
 * Used to mark a function as combinable.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.FUNCTION,
)
annotation class Combinable

internal fun KClass<*>.findRootScreenClass(): KClass<*> {
    var current: Class<*>? = this.java
    var result: Class<*> = current ?: return this

    while (current != null && current != Any::class.java) {
        val superclass = current.superclass
        if (superclass == Screen::class.java) return current.kotlin
        if (Screen::class.java.isInterface && current.interfaces.contains(Screen::class.java)) {
            return current.kotlin
        }
        result = current
        current = superclass
    }
    return result.kotlin
}


@OptIn(KoinInternalApi::class)
@Composable
fun <T : Screen> T.Combine(
    slot: @Composable T.() -> Unit
) {
    this.slot()

    val targetRootClass = remember(this) { this::class.findRootScreenClass() }
    val siblings = remember(this, targetRootClass) {
        getKoin().scopeRegistry.rootScope.getAll<Screen>(targetRootClass)
            .filter { it !== this }
    }

    siblings.forEach { sibling ->
        @Suppress("UNCHECKED_CAST")
        (sibling as? T)?.slot()
    }
}

@OptIn(KoinInternalApi::class)
fun <T : Screen> T.combineNonComposable(
    slot: T.() -> Unit
) {
    this.slot()

    val targetRootClass =  this::class.findRootScreenClass()
    val siblings = getKoin().scopeRegistry.rootScope.getAll<Screen>(targetRootClass)
        .filter { it !== this }

    siblings.forEach { sibling ->
        @Suppress("UNCHECKED_CAST")
        (sibling as? T)?.slot()
    }
}