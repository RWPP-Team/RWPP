/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2.scrollbar.controller

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State

@Stable
interface StateController<IndicatorValue> {
    val thumbSizeNormalized: State<Float>
    val thumbOffsetNormalized: State<Float>
    val thumbIsInAction: State<Boolean>
    val isSelected: State<Boolean>

    fun indicatorValue(): IndicatorValue
    fun onDraggableState(deltaPixels: Float, maxLengthPixels: Float)
    fun onDragStarted(offsetPixels: Float, maxLengthPixels: Float)
    fun onDragStopped()
}