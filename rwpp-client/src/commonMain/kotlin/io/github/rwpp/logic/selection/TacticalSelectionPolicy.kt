/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

/** Use the raw native HP values; shields and unfinished build progress are separate statistics. */
internal fun isLowHealth(hp: Float, maxHp: Float): Boolean =
    hp.isFinite() && maxHp.isFinite() && hp > 0f && maxHp > 0f && hp <= maxHp * 0.30f

internal fun intersectsSelectionViewport(
    x: Float, y: Float, radius: Float, left: Float, top: Float, width: Float, height: Float,
): Boolean = x + radius >= left && x - radius <= left + width &&
        y + radius >= top && y - radius <= top + height
