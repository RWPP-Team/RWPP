/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.rwpp.appKoin
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.screen.ScreenNavigator
import io.github.rwpp.ui.color.getTeamColor

object UI : Initialization, IUserInterface {
    @Deprecated("Will be removed once we don't need anymore")
    internal var backgroundTransparency by mutableFloatStateOf(appKoin.get<Settings>().backgroundTransparency)
    internal var selectedColorSchemeName by mutableStateOf(appKoin.get<Settings>().selectedTheme ?: "RWPP")

    lateinit var globalNavigator: ScreenNavigator

    var question by mutableStateOf<Question?>(null)
        internal set
    var warning by mutableStateOf<Warning?>(null)
        internal set
    var dialogContent by mutableStateOf<(@Composable (dismiss: () -> Unit) -> Unit)?>(null)
        internal set
    var chatMessages by mutableStateOf(AnnotatedString(""))
        internal set
    var inspectingMap: GameMap? by mutableStateOf(null)

    var backgroundImagePath by mutableStateOf(appKoin.get<Settings>().backgroundImagePath ?: "")

    override fun showWarning(reason: String, isKicked: Boolean) {
        synchronized(UI) {
            warning = Warning(reason, isKicked)
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

    private val relayRegex = Regex("""R\d+""")

    fun onReceiveChatMessage(sender: String,  message: String, color: Int) {
        val configIO = appKoin.get<ConfigIO>()
        synchronized(UI) {
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

    }

    /**
     * @see [showQuestion]
     */
    class Question(val title: String, val message: String, val callback: (String?) -> Unit)

    /**
     * @see [showWarning]
     */
    class Warning(val reason: String, val isKicked: Boolean = false)

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
                    text = "快速选择",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                val game = koinInject<Game>()
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MiniSelectButton("全", Color(0xFF9C27B0)) {
                        selectUnitByMovementType(
                            game,
                            null
                        )
                    }
                    MiniSelectButton("建", Color(0xFF795548)) {
                        selectUnitByMovementType(
                            game,
                            setOf(MovementType.NONE)
                        )
                    }
                    MiniSelectButton("海", Color(0xFF1E88E5)) {
                        selectUnitByMovementType(game,
                            setOf(
                                MovementType.WATER,
                                MovementType.OVER_CLIFF_WATER,
                                MovementType.HOVER
                            )
                        )
                    }
                    MiniSelectButton("空", Color(0xFF4FC3F7)) {
                        selectUnitByMovementType(game, setOf(MovementType.AIR))
                    }
                    MiniSelectButton("陆", Color(0xFF43A047)) {
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
