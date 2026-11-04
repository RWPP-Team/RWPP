/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.gameFramework.mod.ModManager

/** The native mod manager began reloading mod data. */
class ReloadModEvent(
    val manager: ModManager? = null,
    val preserveMissing: Boolean = false,
    val metadataOnly: Boolean = false,
) : GameEvent()

/** The native mod manager finished reloading mod data. */
class ReloadModFinishedEvent(
    val manager: ModManager? = null,
    val preserveMissing: Boolean = false,
    val metadataOnly: Boolean = false,
) : GameEvent()

/** The native mod manager failed while reloading mod data. */
class ModReloadFailedEvent(
    val manager: ModManager,
    val preserveMissing: Boolean,
    val metadataOnly: Boolean,
    val error: Throwable,
) : GameEvent()
