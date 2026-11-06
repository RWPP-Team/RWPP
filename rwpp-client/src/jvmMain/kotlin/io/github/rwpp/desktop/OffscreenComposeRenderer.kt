/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION")

package io.github.rwpp.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.scene.skia.InputMethodEndCompositionWorkaround
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import io.github.rwpp.api.event.broadcastIn
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.ui.RenderThreadDispatcher
import io.github.rwpp.ui.desktopComposeKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.skia.*
import org.lwjgl.opengl.*
import org.newdawn.slick.Input
import org.newdawn.slick.InputListener
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyListener
import java.awt.im.InputContext
import java.awt.im.InputMethodRequests
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.SwingUtilities

class OffscreenComposeRenderer : AutoCloseable, InputListener {

    private val renderDispatcher = RenderThreadDispatcher()
    private val renderScope = CoroutineScope(SupervisorJob() + renderDispatcher)

    private val directContext = DirectContext.makeGL()

    /** OpenGL framebuffer size in device pixels (Compose scene size is expressed in the same pixels). */
    var framebufferWidth: Int = 0
        private set
    var framebufferHeight: Int = 0
        private set

    lateinit var _input: Input

    private var textureId: Int = 0
    private var fboId: Int = 0
    private var needRender = true
    private lateinit var surface: Surface
    private lateinit var renderTarget: BackendRenderTarget
    private lateinit var canvas: Canvas
    private fun createImeDialog(): ImeDialog = ImeDialog(
        Window.getWindows().filterIsInstance<JFrame>().firstOrNull { it.isDisplayable }
    ).apply {
        isUndecorated = true
        opacity = 0.0f
        setSize(1, 1)
        // The game owns the display in immersive fullscreen; a topmost window stays above it
        // instead of pushing the game out of the foreground.
        isAlwaysOnTop = true
        // This offscreen host has no visible AWT window. Keep a native peer alive so AWT
        // cannot retire and replace the EDT while the scene and its GL dispatcher still exist.
        addNotify()
    }

    val platformContext = OffscreenAwtPlatformContext {
        IntSize(framebufferWidth, framebufferHeight)
    }

    @OptIn(InternalComposeUiApi::class)
    private val frameRecomposer = FrameRecomposer(renderScope.coroutineContext) { needRender = true }

    @OptIn(InternalComposeUiApi::class)
    private val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        size = IntSize(framebufferWidth, framebufferHeight),
        density = Density(
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
        ),
        platformContext = platformContext,
        invalidateLayout = { needRender = true },
        invalidateDraw = { needRender = true }
    )

    private var drawX = 0f
    private var drawY = 0f

    private val viewportBuffer = ByteBuffer.allocateDirect(Int.SIZE_BYTES * 4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()

    private val imeDialog: ImeDialog = createImeDialog()

    private var lastX = 0
    private var lastY = 0

    private fun getDisplayWidth(): Int = runCatching {
        Display.getWidth()
    }.getOrDefault(framebufferWidth).coerceAtLeast(1)

    private fun getDisplayHeight(): Int = runCatching {
        Display.getHeight()
    }.getOrDefault(framebufferHeight).coerceAtLeast(1)

    /** Reads the active GL viewport, which is in physical pixels even on scaled AWT desktops. */
    private fun getCurrentViewport(): IntArray? = runCatching {
        viewportBuffer.clear()
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer)
        intArrayOf(viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3))
    }.getOrNull()?.takeIf { it[2] > 0 && it[3] > 0 }

    @OptIn(InternalComposeUiApi::class)
    fun updateBounds(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return

        if (this.framebufferWidth == newWidth && this.framebufferHeight == newHeight) {
            return
        }

        this.framebufferWidth = newWidth
        this.framebufferHeight = newHeight
        platformContext.updateSize(IntSize(newWidth, newHeight))

        initOpenGLFrameBuffer(framebufferWidth, framebufferHeight)

        scene.size = IntSize(framebufferWidth, framebufferHeight)
        needRender = true
    }

    private fun initOpenGLFrameBuffer(pWidth: Int, pHeight: Int) {
        cleanupOpenGLResources()

        textureId = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            pWidth,
            pHeight,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            null as ByteBuffer?
        )
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)

        fboId = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

        renderTarget = BackendRenderTarget.makeGL(
            pWidth, pHeight, 0, 8, fboId, FramebufferFormat.GR_GL_RGBA8
        )

        surface = Surface.makeFromBackendRenderTarget(
            directContext, renderTarget, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
        )!!
        canvas = surface.canvas.asComposeCanvas()
    }

    private fun cleanupOpenGLResources() {
        if (::surface.isInitialized) runCatching { surface.close() }
        if (::renderTarget.isInitialized) runCatching { renderTarget.close() }

        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId)
            fboId = 0
        }
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId)
            textureId = 0
        }
    }

    @OptIn(InternalComposeUiApi::class)
    fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        scene.setContent(content = content)
        scene.size = IntSize(framebufferWidth, framebufferHeight)
    }

    @OptIn(InternalComposeUiApi::class)
    fun render() {
        io.github.rwpp.ui.HudFrameClock.tick()
        renderDispatcher.drain()
        renderDispatcher.drain()
        frameRecomposer.performFrame(System.nanoTime())
        renderDispatcher.drain()
        val viewport = getCurrentViewport()
        val width = viewport?.get(2) ?: getDisplayWidth()
        val height = viewport?.get(3) ?: getDisplayHeight()

        if (width != framebufferWidth || height != framebufferHeight) {
            updateBounds(width, height)
            needRender = true
        }
        scene.measureAndLayout()

        val hasInvalidations = scene.hasInvalidations()
        if (!needRender && !hasInvalidations && !shouldUpdate()) return
        needRender = false

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushClientAttrib(-1)

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId)
        GL11.glViewport(0, 0, framebufferWidth, framebufferHeight)

        surface.canvas.clear(Color.TRANSPARENT)

        scene.size = IntSize(framebufferWidth, framebufferHeight)
        scene.draw(canvas)

        directContext.flush()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

        directContext.resetGLAll()
        resetGLStateToFixedFunction()

        GL11.glPopClientAttrib()
        GL11.glPopAttrib()
    }

    fun drawToScreen() {
        val viewport = getCurrentViewport()
        val viewportX = viewport?.get(0) ?: 0
        val viewportY = viewport?.get(1) ?: 0
        val winW = (viewport?.get(2) ?: framebufferWidth.coerceAtLeast(1)).toFloat()
        val winH = (viewport?.get(3) ?: framebufferHeight.coerceAtLeast(1)).toFloat()

        this.drawX = 0f
        this.drawY = 0f

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushClientAttrib(-1)

        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_LIGHTING)

        val physWinW = winW.toInt().coerceAtLeast(1)
        val physWinH = winH.toInt().coerceAtLeast(1)
        GL11.glViewport(viewportX, viewportY, physWinW, physWinH)

        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId)

        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glColor4f(1f, 1f, 1f, 1f)

        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, winW.toDouble(), winH.toDouble(), 0.0, -1.0, 1.0)

        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, 0f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(winW, 0f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(winW, winH)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, winH)
        GL11.glEnd()

        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)

        resetGLStateToFixedFunction()

        GL11.glPopClientAttrib()
        GL11.glPopAttrib()
    }

    private fun resetGLStateToFixedFunction() {
        runCatching {
            GL33.glBindSampler(0, 0)
        }

        GL20.glUseProgram(0)
        GL30.glBindVertexArray(0)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)

        for (i in 0..15) {
            GL20.glDisableVertexAttribArray(i)
        }

        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)

        GL11.glDisable(32928) // GL_FRAMEBUFFER_SRGB
        GL11.glDisable(GL13.GL_MULTISAMPLE)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)

        for (i in 0..7) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0)

        GL11.glMatrixMode(GL11.GL_TEXTURE)
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
    }

    @OptIn(InternalComposeUiApi::class)
    fun shouldUpdate() = frameRecomposer.hasPendingWork() || scene.hasInvalidations()

    /**
     * Gameplay owns the native window, so system IME hotkeys are suppressed there. A focused
     * Compose text field owns its own hidden AWT IME window instead; the gate must stay open
     * for the whole text session, otherwise Chinese input loses its composition events.
     */
    fun syncSystemShortcutGate(): Boolean {
        val gameplay = io.github.rwpp.ui.navigatorHoldsGameScreen()
        DesktopSystemShortcutGate.update(gameplay && !imeDialog.inputMethodsEnabled)
        return gameplay
    }

    private fun calculateLogicalOffset(x: Float, y: Float): Offset {
        val relX = x - drawX
        val relY = y - drawY

        // Slick input coordinates follow Display's window units, while scene coordinates are pixels.
        val currentRenderW = getDisplayWidth().toFloat()
        val currentRenderH = getDisplayHeight().toFloat()

        val scaleX = framebufferWidth.toFloat() / currentRenderW
        val scaleY = framebufferHeight.toFloat() / currentRenderH

        return Offset(relX * scaleX, relY * scaleY)
    }

    @OptIn(InternalComposeUiApi::class)
    override fun close() {
        DesktopSystemShortcutGate.restore()
        scene.close()
        frameRecomposer.close()
        renderScope.cancel()
        cleanupOpenGLResources()
        directContext.close()
        SwingUtilities.invokeLater {
            imeDialog.dispose()
        }
    }

    @OptIn(InternalComposeUiApi::class)
    override fun mouseMoved(oldx: Int, oldy: Int, newx: Int, newy: Int) {
        sendPointerEvent(PointerEventType.Move, newx.toFloat(), newy.toFloat())
        if (menuOwnsInput()) _input.consumeEvent()
    }

    @OptIn(InternalComposeUiApi::class)
    override fun mouseDragged(oldx: Int, oldy: Int, newx: Int, newy: Int) {
        val button = when {
            _input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON) -> Input.MOUSE_LEFT_BUTTON
            _input.isMouseButtonDown(Input.MOUSE_RIGHT_BUTTON) -> Input.MOUSE_RIGHT_BUTTON
            else -> -1
        }
        val result = sendPointerEvent(PointerEventType.Move, newx.toFloat(), newy.toFloat(), slickButton = button)
        if (!io.github.rwpp.game.building.BuildingPlanningController.hasDesktopMouseCapture() &&
            (result.anyChangeConsumed || menuOwnsInput())
        ) _input.consumeEvent()
    }

    @OptIn(InternalComposeUiApi::class)
    override fun mousePressed(button: Int, x: Int, y: Int) {
        SwingUtilities.invokeLater {
            imeDialog.resetFocus()
        }
        val result = sendPointerEvent(PointerEventType.Press, x.toFloat(), y.toFloat(), slickButton = button)
        if (result.anyChangeConsumed || menuOwnsInput()) _input.consumeEvent()
    }

    @OptIn(InternalComposeUiApi::class)
    override fun mouseReleased(button: Int, x: Int, y: Int) {
        sendPointerEvent(PointerEventType.Release, x.toFloat(), y.toFloat(), slickButton = button)
        // Selection can reveal a consuming HUD between native press and release.
        // Always forward button-up so the native listener cannot retain a held button.
    }

    @OptIn(InternalComposeUiApi::class)
    override fun mouseWheelMoved(change: Int) {
        val result = sendPointerEvent(
            PointerEventType.Scroll,
            _input.mouseX.toFloat(), _input.mouseY.toFloat(),
            scrollDelta = Offset(0f, -change.toFloat() * 0.01f)
        )
        if (result.anyChangeConsumed || menuOwnsInput()) _input.consumeEvent()
    }

    @OptIn(InternalComposeUiApi::class)
    private fun sendPointerEvent(
        type: PointerEventType,
        x: Float,
        y: Float,
        slickButton: Int = -1,
        scrollDelta: Offset = Offset.Zero
    ): PointerEventResult {
        lastX = x.toInt()
        lastY = y.toInt()

        val button = when (slickButton) {
            Input.MOUSE_LEFT_BUTTON -> PointerButton.Primary
            Input.MOUSE_RIGHT_BUTTON -> PointerButton.Secondary
            Input.MOUSE_MIDDLE_BUTTON -> PointerButton.Tertiary
            else -> null
        }

        needRender = true
        return scene.sendPointerEvent(
            eventType = type,
            position = calculateLogicalOffset(x, y),
            scrollDelta = scrollDelta,
            button = button,
            timeMillis = System.currentTimeMillis()
        )
    }

    @OptIn(InternalComposeUiApi::class)
    override fun keyPressed(key: Int, c: Char) {
        sendComposeKeyEvent(KeyEventType.KeyDown, key, c)
    }

    @OptIn(InternalComposeUiApi::class)
    override fun keyReleased(key: Int, c: Char) {
        sendComposeKeyEvent(KeyEventType.KeyUp, key, c)
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun sendComposeKeyEvent(type: KeyEventType, slickKey: Int, c: Char) {
        val composeKey = slickToComposeKey(slickKey)

        val isShift = _input.isKeyDown(Input.KEY_LSHIFT) || _input.isKeyDown(Input.KEY_RSHIFT)
        val isCtrl = _input.isKeyDown(Input.KEY_LCONTROL) || _input.isKeyDown(Input.KEY_RCONTROL)
        val isAlt = _input.isKeyDown(Input.KEY_LALT) || _input.isKeyDown(Input.KEY_RALT)

        val composeKeyEvent = desktopComposeKeyEvent(composeKey, type, c, isCtrl, isAlt, isShift)

        val consumed = scene.sendKeyEvent(composeKeyEvent)
        val blocked = menuOwnsInput()
        // A blocked screen keeps the key away from both the native listener and the
        // RWPP game hotkey bus; releases still reach the game to clear held keys.
        if (!consumed && !blocked) KeyboardEvent(composeKeyEvent).broadcastIn()
        if (type == KeyEventType.KeyDown && (consumed || blocked || composeKey == Key.Escape)) {
            _input.consumeEvent()
        }
        needRender = true
    }

    private fun menuOwnsInput(): Boolean =
        io.github.rwpp.ui.gameInputBlockedByNavigator() ||
                io.github.rwpp.appKoin.get<io.github.rwpp.api.game.Game>().state.state == io.github.rwpp.api.State.Menu ||
                io.github.rwpp.appKoin.get<io.github.rwpp.ui.GameScreen>().showPauseMenu ||
                io.github.rwpp.appKoin.get<io.github.rwpp.ui.GameScreen>().isEditMode ||
                io.github.rwpp.ui.isShowSendMessageDialog

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun sendAwtKeyEvent(e: java.awt.event.KeyEvent, type: KeyEventType) {
        val composeKey = Key(e.keyCode)

        val c = e.keyChar
        val codePoint = if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED && (c.code in 32..126 || c.code > 127)) {
            c.code
        } else {
            0
        }

        val isShift = e.isShiftDown
        val isCtrl = e.isControlDown
        val isAlt = e.isAltDown

        val composeKeyEvent = KeyEvent(
            key = composeKey,
            type = type,
            codePoint = codePoint,
            isCtrlPressed = isCtrl,
            isAltPressed = isAlt,
            isShiftPressed = isShift,
            nativeEvent = e
        )

        scene.sendKeyEvent(composeKeyEvent)
        needRender = true
    }

    private fun slickToComposeKey(slickKey: Int): Key {
        return when (slickKey) {
            Input.KEY_A -> Key.A; Input.KEY_B -> Key.B; Input.KEY_C -> Key.C
            Input.KEY_D -> Key.D; Input.KEY_E -> Key.E; Input.KEY_F -> Key.F
            Input.KEY_G -> Key.G; Input.KEY_H -> Key.H; Input.KEY_I -> Key.I
            Input.KEY_J -> Key.J; Input.KEY_K -> Key.K; Input.KEY_L -> Key.L
            Input.KEY_M -> Key.M; Input.KEY_N -> Key.N; Input.KEY_O -> Key.O
            Input.KEY_P -> Key.P; Input.KEY_Q -> Key.Q; Input.KEY_R -> Key.R
            Input.KEY_S -> Key.S; Input.KEY_T -> Key.T; Input.KEY_U -> Key.U
            Input.KEY_V -> Key.V; Input.KEY_W -> Key.W; Input.KEY_X -> Key.X
            Input.KEY_Y -> Key.Y; Input.KEY_Z -> Key.Z
            Input.KEY_0 -> Key.Zero; Input.KEY_1 -> Key.One; Input.KEY_2 -> Key.Two
            Input.KEY_3 -> Key.Three; Input.KEY_4 -> Key.Four; Input.KEY_5 -> Key.Five
            Input.KEY_6 -> Key.Six; Input.KEY_7 -> Key.Seven; Input.KEY_8 -> Key.Eight
            Input.KEY_9 -> Key.Nine
            Input.KEY_F1 -> Key.F1; Input.KEY_F2 -> Key.F2; Input.KEY_F3 -> Key.F3
            Input.KEY_F4 -> Key.F4; Input.KEY_F5 -> Key.F5; Input.KEY_F6 -> Key.F6
            Input.KEY_F7 -> Key.F7; Input.KEY_F8 -> Key.F8; Input.KEY_F9 -> Key.F9
            Input.KEY_F10 -> Key.F10; Input.KEY_F11 -> Key.F11; Input.KEY_F12 -> Key.F12
            Input.KEY_NUMPAD0 -> Key.NumPad0; Input.KEY_NUMPAD1 -> Key.NumPad1; Input.KEY_NUMPAD2 -> Key.NumPad2
            Input.KEY_NUMPAD3 -> Key.NumPad3; Input.KEY_NUMPAD4 -> Key.NumPad4; Input.KEY_NUMPAD5 -> Key.NumPad5
            Input.KEY_NUMPAD6 -> Key.NumPad6; Input.KEY_NUMPAD7 -> Key.NumPad7; Input.KEY_NUMPAD8 -> Key.NumPad8
            Input.KEY_NUMPAD9 -> Key.NumPad9; Input.KEY_NUMPADENTER -> Key.NumPadEnter
            Input.KEY_PRIOR -> Key.PageUp; Input.KEY_NEXT -> Key.PageDown; Input.KEY_INSERT -> Key.Insert
            Input.KEY_ENTER -> Key.Enter; Input.KEY_ESCAPE -> Key.Escape
            Input.KEY_BACK -> Key.Backspace; Input.KEY_DELETE -> Key.Delete
            Input.KEY_TAB -> Key.Tab; Input.KEY_SPACE -> Key.Spacebar
            Input.KEY_MINUS -> Key.Minus; Input.KEY_EQUALS -> Key.Equals
            Input.KEY_LEFT -> Key.DirectionLeft; Input.KEY_RIGHT -> Key.DirectionRight
            Input.KEY_UP -> Key.DirectionUp; Input.KEY_DOWN -> Key.DirectionDown
            Input.KEY_HOME -> Key.MoveHome; Input.KEY_END -> Key.MoveEnd
            Input.KEY_LSHIFT, Input.KEY_RSHIFT -> Key.ShiftLeft
            Input.KEY_LCONTROL, Input.KEY_RCONTROL -> Key.CtrlLeft
            Input.KEY_LALT, Input.KEY_RALT -> Key.AltLeft
            Input.KEY_COMMA -> Key.Comma; Input.KEY_PERIOD -> Key.Period
            Input.KEY_SLASH -> Key.Slash; Input.KEY_SEMICOLON -> Key.Semicolon
            Input.KEY_APOSTROPHE -> Key.Apostrophe; Input.KEY_BACKSLASH -> Key.Backslash
            Input.KEY_GRAVE -> Key.Grave
            else -> Key.Unknown
        }
    }

    override fun mouseClicked(p0: Int, p1: Int, p2: Int, p3: Int) {
        if (menuOwnsInput()) _input.consumeEvent()
    }
    override fun inputEnded() {}
    override fun inputStarted() {}
    override fun isAcceptingInput(): Boolean = true
    override fun setInput(input: Input?) {}
    override fun controllerButtonPressed(controller: Int, button: Int) {}
    override fun controllerButtonReleased(controller: Int, button: Int) {}
    override fun controllerDownPressed(controller: Int) {}
    override fun controllerDownReleased(controller: Int) {}
    override fun controllerLeftPressed(controller: Int) {}
    override fun controllerLeftReleased(controller: Int) {}
    override fun controllerRightPressed(controller: Int) {}
    override fun controllerRightReleased(controller: Int) {}
    override fun controllerUpPressed(controller: Int) {}
    override fun controllerUpReleased(controller: Int) {}

    @OptIn(InternalComposeUiApi::class)
    inner class OffscreenAwtPlatformContext(
        private val getOffscreenSize: () -> IntSize,
    ) : PlatformContext by PlatformContext.Empty() {
        private val windowInfoState = OffscreenWindowInfo(getOffscreenSize())

        override val windowInfo: WindowInfo
            get() = windowInfoState

        fun updateSize(size: IntSize) {
            windowInfoState.containerSize = size
        }

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            imeDialog.startInputMethod(request)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    inner class ImeDialog(owner: JFrame?) : JDialog(owner) {
        private var endCompositionWorkaround: InputMethodEndCompositionWorkaround? = null

        var currentInputMethodRequests: InputMethodRequests? = null
            private set

        private var pendingInputMethodRequest: PlatformTextInputMethodRequest? = null

        var platformTextInputMethodRequest: PlatformTextInputMethodRequest? = null
            private set

        internal val platformComponent = DesktopPlatformComponent()
        private var activeInputSession: Any? = null


        private val focusProxy = javax.swing.JPanel().apply {
            isFocusable = true
            enableInputMethods(false)
            focusTraversalKeysEnabled = false
        }
        private var focusGeneration = 0L

        @Volatile
        var inputMethodsEnabled = false

        init {
            isUndecorated = true
            setSize(1, 1)
            isFocusable = true

            focusTraversalKeysEnabled = false

            enableInputMethods(true)
            add(focusProxy)
            focusProxy.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(event: java.awt.event.FocusEvent) {
                    val generation = focusGeneration
                    SwingUtilities.invokeLater {
                        if (inputMethodsEnabled && generation == focusGeneration) this@ImeDialog.requestFocus()
                    }
                }
            })
            val imeListener = object : InputMethodListener {
                override fun inputMethodTextChanged(event: InputMethodEvent) {
                    if (event.isConsumed) return
                    val request = platformTextInputMethodRequest ?: return
                    val iterator = event.text
                    val text = buildString {
                        if (iterator != null) {
                            var char = iterator.first()
                            while (char != java.text.CharacterIterator.DONE) {
                                append(char)
                                char = iterator.next()
                            }
                        }
                    }
                    val committedCount = event.committedCharacterCount.coerceIn(0, text.length)
                    val committed = text.take(committedCount)
                    val composing = text.drop(committedCount)
                    renderScope.launch {
                        if (platformTextInputMethodRequest !== request) return@launch
                        request.editText {
                            commitText(committed, 1)
                            if (composing.isNotEmpty()) setComposingText(composing, 1)
                        }
                    }
                    event.consume()
                }

                override fun caretPositionChanged(event: InputMethodEvent) {
                    return
                }
            }
            addInputMethodListener(imeListener)

            val keyListener = object : KeyListener {
                override fun keyTyped(e: java.awt.event.KeyEvent) {
                }

                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    val request = platformTextInputMethodRequest
                    renderScope.launch {
                        if (platformTextInputMethodRequest !== request) return@launch
                        val char = e.keyChar

                        if (char != java.awt.event.KeyEvent.CHAR_UNDEFINED && !char.isISOControl()) {
                            request?.editText {
                                commitText(char.toString(), 1)
                            }
                            e.consume()
                        } else {
                            sendAwtKeyEvent(e, KeyEventType.KeyDown)
                        }
                    }
                }

                override fun keyReleased(e: java.awt.event.KeyEvent) {
                    renderScope.launch {
                        sendAwtKeyEvent(e, KeyEventType.KeyUp)
                    }
                }
            }
            addKeyListener(keyListener)

        }

        override fun getInputMethodRequests(): InputMethodRequests? {
            return currentInputMethodRequests
        }

        override fun getInputContext(): InputContext? =
            endCompositionWorkaround?.inputContext ?: super.getInputContext()

        override fun addNotify() {
            super.addNotify()
            endCompositionWorkaround = InputMethodEndCompositionWorkaround.forCurrentEnvironment(
                componentInputContext = { super.getInputContext() }
            )
        }

        suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            val session = Any()
            activeInputSession = session
            pendingInputMethodRequest = request
            val sessionComponent = object : PlatformComponent by platformComponent {
                override fun enableInput(inputMethodRequests: InputMethodRequests) {
                    if (activeInputSession === session) platformComponent.enableInput(inputMethodRequests)
                }

                override fun disableInput() {
                    // Cancellation of a previous field must not clear the newly focused field.
                    if (activeInputSession === session) platformComponent.disableInput()
                }

                override fun endComposition() {
                    if (activeInputSession === session) platformComponent.endComposition()
                }
            }
            try {
                // This service supplies AWT editor queries; text commits are handled explicitly above.
                DesktopTextInputService2(sessionComponent).startInputMethod(request)
            } finally {
                if (activeInputSession === session) {
                    activeInputSession = null
                    pendingInputMethodRequest = null
                }
            }
        }

        fun resetFocus() {
            if (!inputMethodsEnabled) return
            // Force a real AWT focus-lost/focus-gained cycle on the same persistent dialog.
            // The proxy never handles text; returning to the dialog reactivates its IME client.
            focusGeneration++
            focusableWindowState = true
            toFront()
            if (focusProxy.isFocusOwner || !focusProxy.requestFocusInWindow()) requestFocus()
        }

        inner class DesktopPlatformComponent : PlatformComponent {
            override val locationOnScreen: Point
                get() = runCatching { this@ImeDialog.locationOnScreen }.getOrDefault(Point(0, 0))

            @OptIn(InternalComposeUiApi::class)
            override val density: Density
                get() = this@OffscreenComposeRenderer.scene.density

            override fun enableInput(inputMethodRequests: InputMethodRequests) {
                currentInputMethodRequests = inputMethodRequests
                platformTextInputMethodRequest = pendingInputMethodRequest
                inputMethodsEnabled = true
                SwingUtilities.invokeLater {
                    if (currentInputMethodRequests !== inputMethodRequests || !inputMethodsEnabled) return@invokeLater
                    isVisible = true
                    resetFocus()
                }
            }

            override fun disableInput() {
                focusGeneration++
                currentInputMethodRequests = null
                inputMethodsEnabled = false
                platformTextInputMethodRequest = null
                SwingUtilities.invokeLater {
                    if (!inputMethodsEnabled) isVisible = false
                }
            }

            override fun endComposition() {
                inputContext?.endComposition()
            }
        }
    }
}

private class OffscreenWindowInfo(initialSize: IntSize) : WindowInfo {
    override var isWindowFocused: Boolean by mutableStateOf(true)
    override var containerSize: IntSize by mutableStateOf(initialSize)
}

