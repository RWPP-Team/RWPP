/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.chat.ChatTranscriptGeneration
import io.github.rwpp.screen.ScreenNavigator
import io.github.rwpp.ui.color.getTeamColor

object UI : Initialization, IUserInterface {
    /** Live HUD panel opacity; the settings slider writes here so panels restyle immediately. */
    internal var backgroundTransparency: Float
        get() = HudOpacity.value
        set(value) {
            HudOpacity.value = value
        }
    internal var selectedColorSchemeName by mutableStateOf(appKoin.get<Settings>().selectedTheme ?: "RWPP")

    /**
     * Published when the app composition is applied. The window layer reads this as Compose
     * state, so a GameScreen entering the back stack hides the wallpaper in the same frame.
     */
    internal var liveNavigator by mutableStateOf<ScreenNavigator?>(null)
        private set

    /** Null clears the published navigator when its composition is disposed. */
    internal fun publishNavigator(navigator: ScreenNavigator?) {
        liveNavigator = navigator
    }

    var question by mutableStateOf<Question?>(null)
        internal set
    var warning by mutableStateOf<Warning?>(null)
        internal set
    var dialogContent by mutableStateOf<(@Composable (dismiss: () -> Unit) -> Unit)?>(null)
        internal set
    var chatMessages by mutableStateOf(AnnotatedString(""))
        internal set
    var inspectingMap: GameMap? by mutableStateOf(null)
    var showPlayerList by mutableStateOf(false)

    var backgroundImagePath by mutableStateOf(appKoin.get<Settings>().backgroundImagePath ?: "")

    /** Generation captured before dispatching a chat line so queued messages cannot cross a clear. */
    private val chatTranscriptGeneration = ChatTranscriptGeneration()

    internal val currentChatTranscriptGeneration: Long
        get() = synchronized(UI) { chatTranscriptGeneration.snapshot() }

    internal fun clearChatMessages() {
        synchronized(UI) {
            chatTranscriptGeneration.clear()
            chatMessages = AnnotatedString("")
        }
    }

    override fun showWarning(reason: String, isKicked: Boolean) {
        synchronized(UI) {
            warning = Warning(reason, isKicked)
        }
    }

    internal fun showNativeMessage(title: String?, message: String?) {
        val text = listOfNotNull(title, message).filter(String::isNotBlank).joinToString("\n")
        if (text.isBlank()) return
        synchronized(UI) {
            warning = Warning(text, title == "Kicked", nativeMessage = message)
        }
    }

    override fun showQuestion(title: String, message: String, callback: (String?) -> Unit) {
        synchronized(UI) {
            question = Question(title, message, callback)
        }
    }

    override fun showDialog(content: (@Composable (dismiss: () -> Unit) -> Unit)?) {
        synchronized(UI) {
            dialogContent = content
        }
    }

    fun showQuestion(title: String, message: String, allowEmptyReply: Boolean, callback: (String?) -> Unit) {
        synchronized(UI) { question = Question(title, message, callback, allowEmptyReply) }
    }

    /** Dismissing a prompt owns only that prompt, never the screen beneath it. */
    fun dismissQuestion(closing: Question?) {
        synchronized(UI) { if (question === closing) question = null }
        closing?.replyOnce(null)
    }

    private val relayRegex = Regex("""R\d+""")

    fun onReceiveChatMessage(
        sender: String,
        message: String,
        color: Int,
        expectedGeneration: Long = currentChatTranscriptGeneration,
    ): Boolean {
        synchronized(UI) {
            if (!chatTranscriptGeneration.accepts(expectedGeneration)) return false
            val configIO = appKoin.get<ConfigIO>()
            chatMessages =
                buildAnnotatedString {
                    if (sender == "RELAY_CN-ADMIN") {
                        val result = relayRegex.find(message)?.value

                        if (!result.isNullOrBlank()) {
                            configIO.setGameConfig("lastNetworkIP", result)
                        }
                    }

                    if (sender.isNotBlank()) {
                        withStyle(
                            style = SpanStyle(
                                color = getTeamColor(color),
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append("$sender: ")
                        }
                    }

                    withStyle(style = SpanStyle(color = Color.White)) {
                        append(message)
                    }

                    append("\n")
                } + chatMessages
        }
        return true
    }

    /**
     * @see [showQuestion]
     */
    class Question(
        val title: String, val message: String, val callback: (String?) -> Unit,
        val allowEmptyReply: Boolean = false
    ) {
        private val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun replyOnce(answer: String?): Boolean {
            if (!answered.compareAndSet(false, true)) return false
            callback(answer)
            return true
        }
    }

    /**
     * @see [showWarning]
     */
    class Warning(val reason: String, val isKicked: Boolean = false, val nativeMessage: String? = null)

    override fun init() {
    }
}

/*
open class UIProvider {
    @Composable
    open fun InGameComposeContent() {
        val settings = koinInject<Settings>()
        if (settings.enableQuickSelectMenu) {
            TinyQuickSelectMenu()
        }
    }
    @Composable
    fun TinyQuickSelectMenu() {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val scaleFactor = when {
                maxHeight > 1200.dp -> 2.5f
                maxHeight > 600.dp -> 1.5f
                else -> 1.0f
            }

            Column(
                modifier = Modifier
                    .padding(8.dp * scaleFactor)
                    .offset(x = if (scaleFactor == 1.0f) (-40).dp else 0.dp)
                    .scale(scaleFactor)
                    .width(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = readI18n("rwpp.hud.quickSelect.title"),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                val game = koinInject<Game>()
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MiniSelectButton(readI18n("rwpp.hud.quickSelect.allShort"), Color(0xFF9C27B0)) {
                        selectUnitByMovementType(
                            game,
                            null
                        )
                    }
                    MiniSelectButton(readI18n("rwpp.hud.quickSelect.buildingsShort"), Color(0xFF795548)) {
                        selectUnitByMovementType(
                            game,
                            setOf(MovementType.NONE)
                        )
                    }
                    MiniSelectButton(readI18n("rwpp.hud.quickSelect.navyShort"), Color(0xFF1E88E5)) {
                        selectUnitByMovementType(game,
                            setOf(
                                MovementType.WATER,
                                MovementType.OVER_CLIFF_WATER,
                                MovementType.HOVER
                            )
                        )
                    }
                    MiniSelectButton(readI18n("rwpp.hud.quickSelect.airShort"), Color(0xFF4FC3F7)) {
                        selectUnitByMovementType(game, setOf(MovementType.AIR))
                    }
                    MiniSelectButton(readI18n("rwpp.hud.quickSelect.landShort"), Color(0xFF43A047)) {
                        selectUnitByMovementType(game,
                            setOf(
                                MovementType.LAND,
                                MovementType.HOVER,
                                MovementType.OVER_CLIFF,
                                MovementType.OVER_CLIFF_WATER
                            )
                        )
                    }
                }
            }
        }
    }

    private fun selectUnitByMovementType(game: Game, typeSet: Set<MovementType>?) {
        GameScope.launch {
            val interfaceEngine = GameEngine.getInstance().interfaceEngine
            interfaceEngine.selectedUnits.clear()
            com.corrodinggames.rts.gameFramework.GameObject.fastGameObjectList.forEach {
                if (it is GameUnit &&
                    !it.dead &&
                    !it.getUnitTypeInterface().isBuilder &&
                    (typeSet?.contains(it.getUnitTypeInterface().movementType)
                        ?: (it.getUnitTypeInterface().movementType != MovementType.NONE)) &&
                    it.team == game.gameRoom.localPlayer
                ) interfaceEngine.selectUnit(it)
            }
        }
    }

    @Composable
    fun MiniSelectButton(label: String, color: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.7f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}*/
