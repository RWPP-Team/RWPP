/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopCoreHooksTest {
    @Test
    fun `all core event hooks transform their named game library targets`() {
        val cases =
            listOf(
                "com/corrodinggames/rts/gameFramework/GameEngine" to listOf(GameEngineLogMixin::class.java),
                "com/corrodinggames/rts/game/RWGameEngine" to listOf(GameResumeMixin::class.java),
                "com/corrodinggames/rts/java/MainLauncher" to listOf(MainLauncherMixin::class.java),
                "com/corrodinggames/rts/gameFramework/network/NetworkCallbacks" to listOf(PlayerAddedMixin::class.java),
                "com/corrodinggames/rts/gameFramework/trigger/TriggerActivator" to listOf(MapTriggerMixin::class.java),
                "com/corrodinggames/librocket/scripts/ScriptEngine" to listOf(ScriptThreadAccessMixin::class.java),
            )
        val classLoader = javaClass.classLoader

        for ((className, mixins) in cases) {
            val originalBytes =
                requireNotNull(classLoader.getResourceAsStream("$className.class")) {
                    "named game-lib target $className is missing from the JVM test classpath"
                }.use { it.readBytes() }
            AsmRegistry.clear()
            try {
                mixins.forEach(AsmRegistry::register)
                val transformedBytes = AsmProcessor().transform(className, originalBytes, classLoader)
                assertTrue(
                    !originalBytes.contentEquals(transformedBytes),
                    "the migrated core hook should transform $className",
                )
            } finally {
                AsmRegistry.clear()
            }
        }
    }
}