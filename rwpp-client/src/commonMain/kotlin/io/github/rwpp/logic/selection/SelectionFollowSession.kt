/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

/**
 * Holds the unit group captured when follow is enabled. Later selection changes do not replace it;
 * if the current anchor dies, the next living member is chosen in captured order, wrapping once.
 */
internal class SelectionFollowSession<T>(capturedUnits: List<T>) {
    val capturedUnits: List<T> = capturedUnits.toList()
    var currentIndex: Int? = null
        private set

    val currentAnchor: T? get() = currentIndex?.let(capturedUnits::getOrNull)

    fun refreshAnchor(isAlive: (T) -> Boolean): T? {
        val current = currentAnchor
        if (current != null && isAlive(current)) return current
        if (capturedUnits.isEmpty()) {
            currentIndex = null
            return null
        }

        val start = currentIndex ?: -1
        for (offset in 1..capturedUnits.size) {
            val candidateIndex = (start + offset).mod(capturedUnits.size)
            val candidate = capturedUnits[candidateIndex]
            if (isAlive(candidate)) {
                currentIndex = candidateIndex
                return candidate
            }
        }
        currentIndex = null
        return null
    }
}
