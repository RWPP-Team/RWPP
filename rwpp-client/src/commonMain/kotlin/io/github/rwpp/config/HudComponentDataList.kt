/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.config

import io.github.rwpp.api.config.Config

import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

@Single
@Serializable
data class HudComponentDataList(
    var hudComponentDataList: MutableList<HudComponentData> = mutableListOf(),
    var defaultsInitialized: Boolean = false,
    var selectionPanelsInitialized: Boolean = false,
    var observerStatisticsInitialized: Boolean = false,
) : Config
