/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api

import java.util.*

/** Tracks instances, not their classes: late modules catch up without replaying global engine events. */
internal class InitializationRegistry {
    private val initialized = Collections.newSetFromMap(IdentityHashMap<Initialization, Boolean>())
    private val ready = Collections.newSetFromMap(IdentityHashMap<Initialization, Boolean>())

    fun initialize(components: List<Initialization>, engineReady: Boolean) {
        for (component in components) {
            if (initialized.add(component)) {
                try {
                    component.init()
                } catch (failure: Throwable) {
                    initialized.remove(component); throw failure
                }
            }
        }
        if (engineReady) for (component in components) {
            if (ready.add(component)) {
                try {
                    component.onGameLoaded()
                } catch (failure: Throwable) {
                    ready.remove(component); throw failure
                }
            }
        }
    }
}
