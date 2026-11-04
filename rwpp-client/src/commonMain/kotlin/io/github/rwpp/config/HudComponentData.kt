/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable

/**
 * Data class representing the serializable state of a HUD component.
 *
 * @property id Unique identifier for the component type/instance.
 * @property xDp Legacy absolute coordinate, migrated when the viewport is first measured.
 * @property yDp Legacy absolute coordinate, migrated when the viewport is first measured.
 * @property scale The scale factor of the component.
 */
@Serializable
data class HudComponentData(
    var id: String, var xDp: Float, var yDp: Float, var scale: Float = 1f,
    val instanceId: String = java.util.UUID.randomUUID().toString(),
    /** Position within the remaining travel area: 0 = start, 0.5 = center, 1 = end. */
    var relativeX: Float? = null,
    var relativeY: Float? = null,
)
