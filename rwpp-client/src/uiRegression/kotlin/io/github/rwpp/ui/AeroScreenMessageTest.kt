/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受  RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AeroScreenMessageTest {
    @Test
    fun messageFadesInThenHoldsAtFullOpacity() {
        assertEquals(0f, screenMessageAlpha(ageMillis = 0, remainingTicks = 100f, animated = true))
        assertEquals(0.5f, screenMessageAlpha(ageMillis = 70, remainingTicks = 100f, animated = true))
        assertEquals(1f, screenMessageAlpha(ageMillis = 140, remainingTicks = 100f, animated = true))
        assertEquals(1f, screenMessageAlpha(ageMillis = 900, remainingTicks = 100f, animated = true))
    }

    @Test
    fun shortTransientMessagesFadeOutBeforeTheyExpire() {
        assertEquals(1f, screenMessageAlpha(ageMillis = 500, remainingTicks = 18f, animated = true))
        assertEquals(0.5f, screenMessageAlpha(ageMillis = 500, remainingTicks = 9f, animated = true))
        assertEquals(0f, screenMessageAlpha(ageMillis = 500, remainingTicks = 0f, animated = true))
    }

    @Test
    fun disabledAnimationsShowTheMessageAtOnce() {
        assertEquals(1f, screenMessageAlpha(ageMillis = 0, remainingTicks = 0.5f, animated = false))
    }
}
