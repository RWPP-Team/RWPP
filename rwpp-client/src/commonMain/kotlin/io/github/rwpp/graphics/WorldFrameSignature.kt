/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

/** Reusable exact key for the screen-space sprite mask; identity slots compare with `===`. */
internal class WorldUnitMaterialFrameSignature {
    private var numbers = IntArray(128)
    private var identities = arrayOfNulls<Any>(64)
    private var numberCount = 0
    private var identityCount = 0

    fun reset() {
        while (identityCount > 0) identities[--identityCount] = null
        numberCount = 0
    }

    fun put(value: Int) {
        if (numberCount == numbers.size) numbers = numbers.copyOf(numbers.size * 2)
        numbers[numberCount++] = value
    }

    fun put(value: Float) = put(java.lang.Float.floatToRawIntBits(value))

    fun putIdentity(value: Any?) {
        if (identityCount == identities.size) identities = identities.copyOf(identities.size * 2)
        identities[identityCount++] = value
    }

    fun matches(other: WorldUnitMaterialFrameSignature): Boolean {
        if (numberCount != other.numberCount || identityCount != other.identityCount) return false
        for (index in 0 until numberCount) if (numbers[index] != other.numbers[index]) return false
        for (index in 0 until identityCount) if (identities[index] !== other.identities[index]) return false
        return true
    }

    fun copyFrom(other: WorldUnitMaterialFrameSignature) {
        if (numbers.size < other.numberCount) numbers = IntArray(other.numberCount)
        if (identities.size < other.identityCount) identities = arrayOfNulls(other.identityCount)
        for (index in other.identityCount until identityCount) identities[index] = null
        other.numbers.copyInto(numbers, endIndex = other.numberCount)
        other.identities.copyInto(identities, endIndex = other.identityCount)
        numberCount = other.numberCount
        identityCount = other.identityCount
    }
}

