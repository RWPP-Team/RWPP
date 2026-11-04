/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.game.ai

import io.github.rwpp.api.game.ai.NativeModMutationGate
import kim.der.asm.api.annotation.*
import java.util.Iterator

/** Avoids fail-fast traversal when a third-party custom unit's action list is rebuilt concurrently. */
@AsmMixin("com/corrodinggames/rts/game/ai/AI\$5")
object AiUnitMixActionSnapshotMixin {
    private const val ARRAY_LIST_ITERATOR = "java/util/ArrayList.iterator()Ljava/util/Iterator;"

    @WrapOperation(
        method = "isUnitTypeIncluded(Lcom/corrodinggames/rts/game/units/UnitTypeInterface;)Z",
        at = At(InjectionPoint.INVOKE, target = ARRAY_LIST_ITERATOR),
        require = 1,
    )
    fun snapshotUnitActions(actions: ArrayList<*>, @Suppress("UNUSED_PARAMETER") original: Operation<Iterator<*>>) =
        NativeModMutationGate.snapshot(actions).iterator()
}
