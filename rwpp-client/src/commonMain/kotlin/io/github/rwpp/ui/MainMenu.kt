/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.coreVersion
import io.github.rwpp.api.game.Game
import io.github.rwpp.generated.*
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.net.Net
import io.github.rwpp.projectVersion
import io.github.rwpp.screen.Combinable
import io.github.rwpp.screen.Combine
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.widget.v2.*
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single

@Single
class MainMenuScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val net = koinInject<Net>()
        val game = koinInject<Game>()
        val appContext = koinInject<AppContext>()

        AeroScaledContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RWPP",
                            style = TextStyle(
                                fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                ),
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 4.sp
                            )
                        )
                        Text(
                            text = "$projectVersion (CORE $coreVersion)",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroBadge(text = "NET: ONLINE")
                        AeroBadge(text = "SYS: READY", color = MaterialTheme.colorScheme.secondary)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    AeroPanel(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        borderColor = MaterialTheme.colorScheme.primary
                    ) {
                        val state = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                                .scrollable(state, Orientation.Vertical),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AeroHeader(
                                title = "COMBAT OPERATIONS",
                                tag = "PRIMARY SECTOR"
                            )

                            AeroDivider()

                            Combine { OperationButtons() }
                        }
                    }

                    AeroPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        borderColor = MaterialTheme.colorScheme.secondary
                    ) {
                        val state = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                                .scrollable(state, Orientation.Vertical),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AeroHeader(
                                title = "SYSTEM & EXTENSIONS",
                                tag = "DATA CENTER",
                                color = MaterialTheme.colorScheme.secondary
                            )

                            AeroDivider()

                            Combine { ExtensionButtons() }
                        }
                    }
                }

                AeroPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    borderColor = MaterialTheme.colorScheme.outlineVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AeroIconButton(
                                painter = rememberVectorPainter(Icons.Filled.Favorite),
                                modifier = Modifier.padding(4.dp),
                                onClick = { navigator.push<ContributorListScreen>() }
                            )

                            AeroIconButton(
                                painter = painterResource(Res.drawable.qq),
                                modifier = Modifier.padding(4.dp),
                                onClick = {
                                    net.openUriInBrowser("https://qun.qq.com/universal-share/share?ac=1&authKey=QmG6huGEuUos23WJ0WBwh2sUXiP8%2FsLbsX375KEw9HQzdqT2HK2yEY1WS1Me87%2Bw&busi_data=eyJncm91cENvZGUiOiI5Mjc1OTc0OTUiLCJ0b2tlbiI6ImNaZ2dRYXNLd1d3Q0dhS1p0aG9pcVBsOTYxTEJNb0Z4ZDdXT3lmdTljazB2ZEhVQXd5S1dNa0lYVCtwdDZGYXoiLCJ1aW4iOiIxMjI1MzI3ODY2In0%3D&data=ys1-t0OmBONJktYt21HLtehR3nE23CFtG-YUNRRq7Q7aAMmkd-K_EupcjeKeapL9Aob7bXEpuXIp74FsCcUStg&svctype=4&tempid=h5_group_info")
                                }
                            )

                            AeroIconButton(
                                painter = painterResource(Res.drawable.library_30),
                                modifier = Modifier.padding(4.dp),
                                onClick = { net.openUriInBrowser("https://rwpp.netlify.app/") }
                            )

                            AeroIconButton(
                                painter = painterResource(Res.drawable.octocat_30),
                                modifier = Modifier.padding(4.dp),
                                onClick = { net.openUriInBrowser("https://github.com/RWPP-Team/RWPP") }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (game.isGameCouldContinue()) {
                                AeroIconButton(
                                    painter = rememberVectorPainter(Icons.Default.PlayArrow),
                                    modifier = Modifier.padding(4.dp),
                                    onClick = { game.continueGame() }
                                )
                            }

                            AeroIconButton(
                                painter = painterResource(Res.drawable.exit_30),
                                modifier = Modifier.padding(4.dp),
                                onClick = { appContext.exit() }
                            )
                        }
                    }
                }
            }
        }
    }

    @Combinable
    @Composable
    fun OperationButtons() {
        val navigator = LocalScreenNavigator.current
        AeroMenuButton(
            title = readI18n("rwpp.menu.mission"),
            subTitle = "SINGLE PLAYER CAMPAIGN",
            iconPainter = painterResource(Res.drawable.destruction_30),
            onClick = { navigator.push<MissionScreen>() }
        )

        AeroMenuButton(
            title = readI18n("menus.singlePlayer.skirmish"),
            subTitle = "SKIRMISH BATTLE VS AI",
            iconPainter = painterResource(Res.drawable.swords_30),
            onClick = {
                val game = appKoin.get<Game>()
                game.hostNewSinglePlayer(false)
                navigator.push<BattleRoomScreen>()
            }
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.multiplayer"),
            subTitle = "GLOBAL NETWORK WARFARE",
            iconPainter = painterResource(Res.drawable.group_30),
            onClick = { navigator.push<MultiplayerScreen>() }
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.sandbox"),
            subTitle = "TACTICAL EDITOR & SIMULATION",
            iconPainter = painterResource(Res.drawable.edit_square_30),
            onClick = {
                val game = appKoin.get<Game>()
                game.hostNewSinglePlayer(true)
                navigator.push<BattleRoomScreen>()
            }
        )
    }

    @Combinable
    @Composable
    fun ExtensionButtons() {
        val navigator = LocalScreenNavigator.current
        AeroMenuButton(
            title = readI18n("rwpp.menu.mods"),
            subTitle = "MODIFICATIONS",
            iconPainter = painterResource(Res.drawable.dns_30),
            onClick = { navigator.push<ModsScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.browser.resourceBrowser"),
            subTitle = "COMMUNITY HUB",
            iconPainter = painterResource(Res.drawable.public_30),
            onClick = { navigator.push<ResourceBrowserScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.replay"),
            subTitle = "TACTICAL REPLAYS",
            iconVector = Icons.Default.PlayArrow,
            onClick = { navigator.push<ReplaysScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.settings"),
            subTitle = "CORE CONFIGURATION",
            iconVector = Icons.Default.Settings,
            onClick = { navigator.push<SettingsScreen>("RWPP") },
            accentColor = MaterialTheme.colorScheme.secondary
        )
    }
}