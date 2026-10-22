/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable

/**
 * Data class representing the serializable state of a HUD component.
 *
 * @property id Unique identifier for the component type/instance.
 * @property xDp The X coordinate relative to the screen in Dp.
 * @property yDp The Y coordinate relative to the screen in Dp.
 * @property scale The scale factor of the component.
 */
@Serializable
data class HudComponentData(var id: String, var xDp: Float, var yDp: Float, var scale: Float = 1f)
