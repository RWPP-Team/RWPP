/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server

import com.corrodinggames.rts.appFramework.GameActivity
import com.corrodinggames.rts.appFramework.GameViewInterface
import com.corrodinggames.rts.appFramework.TouchEvent
import com.corrodinggames.rts.gameFramework.render.Basic2DGLRenderer
import com.corrodinggames.rts.gameFramework.render.CanvasLike

/**
 * Game view of a process without a window.
 *
 * `RWGameEngine.updateFrameInternal` copies `pendingGameView` into `gameView` and then asks it
 * whether updates are enabled. A dedicated server has no activity, so nothing installs a view and
 * the field stays null, which fails the first frame that reaches that line. This implementation
 * carries only what a windowless frame needs: it reports updates as enabled and answers every
 * drawing question with "nothing to draw".
 */
internal object HeadlessGameView : GameViewInterface {
    // InterfaceEngine still reads pointer coordinates during simulation, even with no window or
    // active touch input. Keep an idle event so the engine's pointer accessors remain valid.
    private val touchEvent = TouchEvent()

    override fun onGameViewAttaching() = Unit

    override fun onGameViewAttached() = Unit

    override fun onGameViewDetached() = Unit

    override fun onWindowFocusChanged(focused: Boolean) = Unit

    override fun isUpdateEnabled(): Boolean = true

    override fun isBufferedRendering(): Boolean = false

    override fun getRenderer(): Basic2DGLRenderer? = null

    override fun usesExternalRendering(): Boolean = false

    override fun isDirectCanvasMode(): Boolean = false

    override fun isFullscreen(): Boolean = false

    override fun getGameThreadSync(): Any = this

    override fun getActivity(): GameActivity? = null

    override fun getTouchEvent(): TouchEvent = touchEvent

    override fun onCanvasDrawError() = Unit

    override fun onMapUnloaded() = Unit

    override fun getCanvas(force: Boolean): CanvasLike? = null

    override fun drawCanvas(canvas: CanvasLike?, force: Boolean) = Unit

    override fun notifyDrawStart(delta: Float, deltaMillis: Int) = Unit

    override fun notifyDrawEnd(delta: Float, deltaMillis: Int) = Unit
}
