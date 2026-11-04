/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun normalizeRoomMaxPlayerCount(value: Float, currentPlayerCount: Int): Int {
    val minimum = currentPlayerCount.coerceIn(0, 100)
    return value.roundToInt().coerceIn(minimum, 100)
}

internal fun formatRoomMaxPlayerCount(value: Float): String = value.roundToInt().toString()

internal fun scheduleRoomAiAddition(
    addAI: (Int) -> Unit,
    count: Int,
    gameScope: CoroutineScope,
    uiScope: CoroutineScope,
    refresh: () -> Unit = {},
) {
    gameScope.launch {
        addAI(count.coerceAtLeast(1))
        uiScope.launch { refresh() }
    }
}
