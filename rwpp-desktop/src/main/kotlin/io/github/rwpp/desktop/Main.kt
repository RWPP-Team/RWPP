/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.rwpp.App
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.ConfigModule
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.GameLoadedEvent
import io.github.rwpp.event.events.QuitGameEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.game.Game
import io.github.rwpp.game.sendChatMessageOrCommand
import io.github.rwpp.game.units.comp.CompModule
import io.github.rwpp.generatedLibDir
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.inject.GameLibraries
import io.github.rwpp.inject.runtime.Builder
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.packageName
import io.github.rwpp.scripts.Render
import io.github.rwpp.ui.InjectConsole
import io.github.rwpp.ui.UI.chatMessages
import io.github.rwpp.ui.Widget
import io.github.rwpp.ui.defaultBuildLogger
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.MenuLoadingView
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.RWTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import org.koin.ksp.generated.module
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess


typealias ColorCompose = androidx.compose.ui.graphics.Color

var native: Boolean = false
var isSendingTeamChat by mutableStateOf(false)
lateinit var mainJFrame: JFrame
lateinit var gameCanvas: Canvas
//lateinit var inGameComposePanel: ComposePanel
var offscreenComposeRenderer: OffscreenComposeRenderer? = null
lateinit var displaySize: Dimension
lateinit var sendMessageDialog: Dialog
lateinit var rwppVisibleSetter: (Boolean) -> Unit
lateinit var focusRequester: FocusRequester
var inGameWidget: Widget? = null
lateinit var inGameWidgetDialog: Dialog
var requireReloadingLib = false

//val cacheModSize = AtomicInteger(0)

fun main(array: Array<String>) {
    if (array.contains("-localgl") && File("opengl32.dll").exists()) { // for only debug
        System.loadLibrary("opengl32")
    }

    logger = LoggerFactory.getLogger(packageName)
    native = array.contains("-native")

    if (native) {
        // 指定输出文件路径
        val outFilePath = "rwpp-log.txt"

        try {
            // 创建文件输出流
            val fileOut = FileOutputStream(outFilePath)

            // 创建PrintStream实例
            val printOut = PrintStream(fileOut)

            // 重定向标准输出和标准错误输出
            System.setOut(printOut)
            System.setErr(printOut)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (!Platform.warnIfNativesMissing()) {
        exitProcess(1)
    }
    Platform.warnAppleSiliconIfNeeded()

    koinInit = true
    appKoin = startKoin {
        logger(org.koin.core.logger.PrintLogger(org.koin.core.logger.Level.ERROR))
        modules(ConfigModule().module, DesktopModule().module, CompModule().module)
    }.koin

    appKoin.get<ConfigIO>().readAllConfig()
    Builder.outputDir = generatedLibDir
    Builder.logger = defaultBuildLogger
    requireReloadingLib = Builder.prepareReloadingLib()

    if (!requireReloadingLib) {
        val app = appKoin.get<AppContext>()
        app.init()
    }

    Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
    File("mods/maps/")
        .walk()
        .filter { it.name.startsWith("generated_") }
        .forEach {
            it.delete()
        }

    val settings = appKoin.get<Settings>()
    Platform.resolveSkikoRenderApi(settings.renderingBackend)?.let { backend ->
        System.setProperty("skiko.renderApi", backend.uppercase())
    }

    displaySize =
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .displayMode
            .run { Dimension(width, height) }

    swingApplication()
}

fun swingApplication() = SwingUtilities.invokeLater {
    val panel = ComposePanel()
    panel.isVisible = true
    panel.size = displaySize.size
    // Opaque so Skiko actually paints over the LWJGL canvas (transparent +
    // JLayeredPane often yields a permanent black window on macOS).
    panel.isOpaque = true
    panel.isFocusable = true
    // Assigned after frames are created (macOS uses a separate UI window).
    lateinit var showUiOverlay: (Boolean) -> Unit
    rwppVisibleSetter = { visible -> showUiOverlay(visible) }
    panel.setContent {
        var isLoading by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf("loading...") }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                if (requireReloadingLib) {
                    runCatching {
                        Builder.init(GameLibraries.`game-lib`, File(System.getProperty("user.dir"), "game-lib.jar"))

                        Builder.logger?.info("Apply config successfully. Now you can restart game to take effect. (已成功应用配置，请重启游戏以生效。)")
                        if (native) {
                            Platform.restartCurrentProcess()
                        }
                    }.onFailure {
                        Builder.logger?.error(it.stackTraceToString())
                    }
                } else {
                    val game = appKoin.get<Game>()
                    game.load { message = it }

                    GameLoadedEvent().broadcastIn()

                    // Brief delay so the window-focus click does not hit menu buttons.
                    kotlinx.coroutines.delay(400)
                    isLoading = false
                    SwingUtilities.invokeLater {
                        showUiOverlay(true)
                    }
                }
            }
        }

        if (requireReloadingLib) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(
                                ColorCompose.Black,
                                androidx.compose.ui.graphics.Color(52, 52, 52),
                                androidx.compose.ui.graphics.Color(2, 48, 32),
                                androidx.compose.ui.graphics.Color(52, 52, 52),
                                ColorCompose.Black
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                RWPPTheme(true) {
                    InjectConsole()
                }
            }
        } else {
            val settings = koinInject<Settings>()
            val isPremium = true
            var backgroundImagePath by remember { mutableStateOf(settings.backgroundImagePath ?: "") }
            val painter = remember(backgroundImagePath) {
                if (backgroundImagePath.isNotBlank() && isPremium) {
                    runCatching { ImageIO.read(File(backgroundImagePath)).toPainter() }.getOrNull()
                } else {
                    null
                }
            }

            Box(
                modifier = Modifier.fillMaxSize().composed {
                    if (backgroundImagePath.isBlank() || !isPremium || painter == null) {
                        background(
                            brush = Brush.verticalGradient(
                                listOf(
                                    ColorCompose.Black,
                                    androidx.compose.ui.graphics.Color(52, 52, 52),
                                    androidx.compose.ui.graphics.Color(2, 48, 32),
                                    androidx.compose.ui.graphics.Color(52, 52, 52),
                                    ColorCompose.Black
                                )
                            )
                        )
                    } else {
                        this
                    }
                },
                contentAlignment = Alignment.Center
            ) {

                if (backgroundImagePath.isNotBlank() && isPremium && painter != null) {
                    Image(
                        painter = painter,
                        null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                if (isLoading) MenuLoadingView(message) else App(isPremium = isPremium) { path ->
                    backgroundImagePath = path
                }

            }
        }
    }

    val window = JFrame()
    val frame = JFrame("退出RWPP")
    frame.setSize(300, 200)
    if (requireReloadingLib) {
        window.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    } else {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)
    }
    window.background = java.awt.Color.BLACK
    val screenSize = Toolkit.getDefaultToolkit().screenSize
    window.minimumSize = Dimension(800, 600)
    window.preferredSize = Dimension(screenSize.width, screenSize.height)
    if (!requireReloadingLib && appKoin.get<Settings>().isFullscreen) {
        window.isUndecorated = true
        window.extendedState = JFrame.MAXIMIZED_BOTH
    } else {
        window.isResizable = true
        // MAXIMIZED_BOTH before first show is unreliable on macOS; set a real size.
        window.setSize(
            (screenSize.width * 0.9).toInt().coerceAtLeast(1024),
            (screenSize.height * 0.9).toInt().coerceAtLeast(720),
        )
        window.setLocationRelativeTo(null)
        window.extendedState = JFrame.MAXIMIZED_BOTH
    }
    window.title = "Rusted Warfare Plus Plus"
    window.iconImage = ImageIO.read(ClassLoader.getSystemResource("composeResources/io.github.rwpp.rwpp_core.generated.resources/drawable/logo.png"))
    if (!requireReloadingLib) {
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                val result = JOptionPane.showConfirmDialog(
                    frame,
                    "Are you sure to exit RWPP? (确定要退出RWPP吗？)",
                    "提示",
                    JOptionPane.YES_NO_OPTION
                )
                if (result == JOptionPane.YES_OPTION) {
                    appKoin.get<AppContext>().exit()
                }
            }
        })
    }

    val canvas = Canvas()
    gameCanvas = canvas
    canvas.size = displaySize.size
    // LWJGL Display.setParent needs a laid-out, displayable Canvas.
    canvas.isVisible = true
    canvas.background = java.awt.Color.BLACK
    canvas.isFocusable = true

    fun syncDisplaySizeFrom(container: java.awt.Container) {
        val w = container.width.coerceAtLeast(1)
        val h = container.height.coerceAtLeast(1)
        if (w > 1 && h > 1) {
            displaySize = Dimension(w, h)
        }
    }

    var onGameWindowResized: () -> Unit = {
        syncDisplaySizeFrom(window.contentPane)
        canvas.setSize(
            window.contentPane.width.coerceAtLeast(1),
            window.contentPane.height.coerceAtLeast(1),
        )
    }

    if (Platform.isMacOS) {
        // Separate Compose from the LWJGL window. Stacking them in one NSWindow
        // paints black and eats input; a click on the black surface was starting Skirmish.
        val uiWindow = JFrame("RWPP")
        uiWindow.iconImage = window.iconImage
        uiWindow.defaultCloseOperation = window.defaultCloseOperation
        uiWindow.background = java.awt.Color.BLACK
        uiWindow.minimumSize = Dimension(800, 600)
        if (!requireReloadingLib && appKoin.get<Settings>().isFullscreen) {
            uiWindow.isUndecorated = true
            uiWindow.extendedState = JFrame.MAXIMIZED_BOTH
        } else {
            uiWindow.isResizable = true
            uiWindow.setSize(window.size)
            uiWindow.setLocationRelativeTo(null)
            uiWindow.extendedState = JFrame.MAXIMIZED_BOTH
        }
        uiWindow.layout = BorderLayout()
        uiWindow.add(panel, BorderLayout.CENTER)
        if (!requireReloadingLib) {
            uiWindow.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                }
            })
        }

        window.layout = BorderLayout()
        window.add(canvas, BorderLayout.CENTER)

        showUiOverlay = { visible ->
            panel.isVisible = visible
            uiWindow.isVisible = visible
            gameCanvas.isVisible = true
            window.isVisible = true
            if (visible) {
                uiWindow.extendedState = uiWindow.extendedState or JFrame.MAXIMIZED_BOTH
                uiWindow.toFront()
                panel.requestFocus()
            } else {
                uiWindow.isVisible = false
                window.toFront()
                gameCanvas.requestFocus()
            }
        }

        window.isVisible = true
        uiWindow.isVisible = true
        SwingUtilities.invokeLater {
            window.extendedState = window.extendedState or JFrame.MAXIMIZED_BOTH
            uiWindow.extendedState = uiWindow.extendedState or JFrame.MAXIMIZED_BOTH
            syncDisplaySizeFrom(window.contentPane)
            canvas.setSize(window.contentPane.width.coerceAtLeast(1), window.contentPane.height.coerceAtLeast(1))
            showUiOverlay(true)
        }
        syncDisplaySizeFrom(window.contentPane)
    } else {
        val layeredPane = JLayeredPane()
        fun layoutLayers() {
            val w = window.contentPane.width.coerceAtLeast(1)
            val h = window.contentPane.height.coerceAtLeast(1)
            layeredPane.setPreferredSize(Dimension(w, h))
            layeredPane.setSize(w, h)
            layeredPane.setBounds(0, 0, w, h)
            canvas.setBounds(0, 0, w, h)
            panel.setBounds(0, 0, w, h)
            panel.setSize(w, h)
            syncDisplaySizeFrom(window.contentPane)
        }

        showUiOverlay = { visible ->
            panel.isVisible = visible
        }
        onGameWindowResized = { layoutLayers() }

        window.layout = BorderLayout()
        window.add(layeredPane, BorderLayout.CENTER)
        layeredPane.add(canvas, JLayeredPane.DEFAULT_LAYER)
        layeredPane.add(panel, JLayeredPane.PALETTE_LAYER)

        window.isVisible = true
        SwingUtilities.invokeLater {
            window.extendedState = window.extendedState or JFrame.MAXIMIZED_BOTH
            layoutLayers()
            panel.requestFocus()
        }
        layoutLayers()
        panel.requestFocus()
    }

    focusRequester = FocusRequester()
    val panel2 = ComposePanel()
    panel2.isOpaque = false
    panel2.isFocusable = true
    panel2.size = Dimension(550, 540)
    panel2.setContent {
        val game = koinInject<Game>()
        RWPPTheme {
            BorderCard(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyDown) {
                            sendMessageDialog.isVisible = false
                        }
                        true
                    },

                backgroundColor = Color(53, 57, 53),
                shape = RectangleShape
            ) {
                var chatMessage by remember { mutableStateOf("") }
                var allChatMessages by remember(chatMessages) {
                    mutableStateOf(TextFieldValue(chatMessages))
                }

                Box {
                    fun onExit() {
                        sendMessageDialog.isVisible = false
                        isSendingTeamChat = false
                    }

                    fun onSendMessage() {
                        if (isSendingTeamChat) {
                            game.gameRoom.sendChatMessage("-t $chatMessage")
                        } else {
                            game.gameRoom.sendChatMessageOrCommand(chatMessage)
                        }

                        chatMessage = ""
                        onExit()
                    }

                    ExitButton {
                        onExit()
                    }

                    GlobalEventChannel.filter(QuitGameEvent::class).onDispose {
                        subscribeAlways { onExit() }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(30.dp))
                        TextField(
                            value = allChatMessages,
                            onValueChange = { allChatMessages = it },
                            readOnly = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            colors = RWTextFieldColors,
                            maxLines = 100
                        )
                        RWSingleOutlinedTextField(
                            label = if (isSendingTeamChat) readI18n("ingame.sendTeamMessage") else readI18n("ingame.sendMessage"),
                            value = chatMessage,
                            focusRequester = focusRequester,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                                .onKeyEvent {
                                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) &&  chatMessage.isNotEmpty()) {
                                        onSendMessage()
                                    }

                                    if (it.key == Key.Escape && it.type == KeyDown) {
                                        sendMessageDialog.isVisible = false
                                    }

                                    true
                                },
                            trailingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    null,
                                    modifier = Modifier.clickable {
                                        onSendMessage()
                                    }
                                )
                            },
                            onValueChange =
                            {
                                chatMessage = it
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            RWTextButton(
                                readI18n("ingame.sendMessage"),
                                modifier = Modifier.padding(5.dp)
                            ) {
                                game.gameRoom.sendChatMessageOrCommand(chatMessage)
                                chatMessage = ""
                                onExit()
                            }

                            RWTextButton(
                                readI18n("ingame.sendTeamMessage"),
                                modifier = Modifier.padding(5.dp)
                            ) {
                                game.gameRoom.sendChatMessage("-t $chatMessage")
                                chatMessage = ""
                                onExit()
                            }
                        }
                    }
                }
            }
        }
    }

    sendMessageDialog = Dialog(window)
    sendMessageDialog.isUndecorated = true
    sendMessageDialog.isFocusable = true
    sendMessageDialog.isVisible = false
    sendMessageDialog.isAlwaysOnTop = true
    sendMessageDialog.size = Dimension(550, 540)
    sendMessageDialog.add(panel2)
    mainJFrame = window
    // LWJGL2 Display.setParent owns the Canvas drawable; BufferStrategy fights it on macOS.
    if (!Platform.isMacOS) {
        runCatching { canvas.createBufferStrategy(2) }
    }

    window.addComponentListener(object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            onGameWindowResized()
            offscreenComposeRenderer?.updateWindowSize(window.contentPane.width, window.contentPane.height)
            resetSendDialogLocation()
        }

        override fun componentMoved(e: ComponentEvent) {
            resetSendDialogLocation()
        }
    })

    onInitInGameWidgetDialog()
}

fun onInitInGameWidgetDialog() = SwingUtilities.invokeLater {
    val panel = ComposePanel()
    panel.isOpaque = false
    panel.isFocusable = true
   // panel.size = Dimension(550, 540)
    panel.setContent {
        RWPPTheme {
            BorderCard(
                modifier = Modifier
                  //  .wrapContentSize()
                    .onKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyDown) {
                            inGameWidgetDialog.isVisible = false
                            true
                        } else {
                            false
                        }
                    }.onGloballyPositioned { coordinates ->
                        SwingUtilities.invokeLater {
                            val scale = getDPIScale()
                            inGameWidgetDialog.preferredSize = Dimension(
                                (coordinates.size.width / scale).toInt(),
                                (coordinates.size.height / scale).toInt()
                            )
                            inGameWidgetDialog.pack()
                            resetInGameWidgetDialogLocation()
                        }
                    },
                backgroundColor = Color(53, 57, 53),
                shape = RectangleShape
            ) {
                inGameWidget?.Render()
            }
        }
    }

    inGameWidgetDialog = Dialog(mainJFrame).apply {
        isUndecorated = true
        isFocusable = true
        isVisible = false
        isAlwaysOnTop = true
        add(panel)
    }

    mainJFrame.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentMoved(e: java.awt.event.ComponentEvent) {
            resetInGameWidgetDialogLocation()
        }

        override fun componentResized(e: java.awt.event.ComponentEvent) {
            resetInGameWidgetDialogLocation()
        }
    })
}

fun resetInGameWidgetDialogLocation() {
    inGameWidgetDialog.setLocation(
        mainJFrame.x + mainJFrame.width / 2 - inGameWidgetDialog.width / 2,
        mainJFrame.y + mainJFrame.height / 2 - inGameWidgetDialog.height / 2
    )
}

fun showSendMessageDialog() {
    sendMessageDialog.isVisible = true
    sendMessageDialog.requestFocus()
    focusRequester.requestFocus()
    resetSendDialogLocation()
}

private fun resetSendDialogLocation() {
    val window = mainJFrame
    sendMessageDialog.setLocation(window.x + window.width / 2 - sendMessageDialog.width / 2, window.y + window.height / 2 - sendMessageDialog.height / 2)
}

fun getDPIScale(): Double {
    // 获取原生系统缩放比例（需考虑多显示器场景）
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = env.defaultScreenDevice
    val config = device.defaultConfiguration

    // 获取系统推荐缩放倍数（Windows 的 % 缩放比例）
    val transform = config.defaultTransform
    return transform.scaleX // 通常 X/Y 缩放一致
}
