/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.rwpp.*
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.net.Net
import io.github.rwpp.generated.*
import io.github.rwpp.screen.Combinable
import io.github.rwpp.screen.Combine
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.widget.WindowManager
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
        val compact = LocalWindowManager.current != WindowManager.Large
        val screenScrollState = rememberScrollState()
        val footerButtonSize = if (compact) 34.dp else 48.dp
        val footerIconSize = if (compact) 15.dp else 22.dp
        val footerButtonPadding = if (compact) 1.dp else 4.dp

        AeroScaledContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (compact) Modifier.verticalScroll(screenScrollState) else Modifier)
                    .padding(if (compact) 12.dp else 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (compact) Arrangement.spacedBy(12.dp) else Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
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
                                fontSize = if (compact) 32.sp else 56.sp,
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

                    if (compact) {
                        Text(
                            text = "${readI18n("rwpp.menu.networkOnline")} · ${readI18n("rwpp.menu.systemReady")}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AeroBadge(text = "${readI18n("rwpp.menu.network")}: ${readI18n("rwpp.menu.online")}")
                            AeroBadge(
                                text = "${readI18n("rwpp.menu.system")}: ${readI18n("rwpp.menu.ready")}",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                if (compact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CombatOperationsPanel(Modifier.fillMaxWidth().height(260.dp))
                        ExtensionPanel(Modifier.fillMaxWidth().height(260.dp))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        CombatOperationsPanel(Modifier.weight(1.2f).fillMaxHeight())
                        ExtensionPanel(Modifier.weight(1f).fillMaxHeight())
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
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AeroIconButton(
                                painter = rememberVectorPainter(Icons.Filled.Favorite),
                                modifier = Modifier.padding(footerButtonPadding),
                                size = footerButtonSize,
                                iconSize = footerIconSize,
                                contentDescription = readI18n("rwpp.contributors.contributors"),
                                onClick = { navigator.push<ContributorListScreen>() }
                            )

                            AeroIconButton(
                                painter = painterResource(Res.drawable.qq),
                                modifier = Modifier.padding(footerButtonPadding),
                                size = footerButtonSize,
                                iconSize = footerIconSize,
                                contentDescription = readI18n("rwpp.menu.communityGroup"),
                                onClick = {
                                    net.openUriInBrowser("https://qun.qq.com/universal-share/share?ac=1&authKey=QmG6huGEuUos23WJ0WBwh2sUXiP8%2FsLbsX375KEw9HQzdqT2HK2yEY1WS1Me87%2Bw&busi_data=eyJncm91cENvZGUiOiI5Mjc1OTc0OTUiLCJ0b2tlbiI6ImNaZ2dRYXNLd1d3Q0dhS1p0aG9pcVBsOTYxTEJNb0Z4ZDdXT3lmdTljazB2ZEhVQXd5S1dNa0lYVCtwdDZGYXoiLCJ1aW4iOiIxMjI1MzI3ODY2In0%3D&data=ys1-t0OmBONJktYt21HLtehR3nE23CFtG-YUNRRq7Q7aAMmkd-K_EupcjeKeapL9Aob7bXEpuXIp74FsCcUStg&svctype=4&tempid=h5_group_info")
                                }
                            )

                            AeroIconButton(
                                painter = painterResource(Res.drawable.library_30),
                                modifier = Modifier.padding(footerButtonPadding),
                                size = footerButtonSize,
                                iconSize = footerIconSize,
                                contentDescription = readI18n("rwpp.menu.website"),
                                onClick = { net.openUriInBrowser("https://rwpp.netlify.app/") }
                            )

                            AeroIconButton(
                                painter = painterResource(Res.drawable.octocat_30),
                                modifier = Modifier.padding(footerButtonPadding),
                                size = footerButtonSize,
                                iconSize = footerIconSize,
                                contentDescription = readI18n("rwpp.menu.sourceCode"),
                                onClick = { net.openUriInBrowser("https://github.com/RWPP-Team/RWPP") }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (game.isGameCouldContinue()) {
                                AeroIconButton(
                                    painter = rememberVectorPainter(Icons.Default.PlayArrow),
                                    modifier = Modifier.padding(footerButtonPadding),
                                    size = footerButtonSize,
                                    iconSize = footerIconSize,
                                    contentDescription = readI18n("rwpp.ingame.continueGame"),
                                    onClick = { game.continueGame() }
                                )
                            }

                            AeroIconButton(
                                painter = painterResource(Res.drawable.exit_30),
                                modifier = Modifier.padding(footerButtonPadding),
                                size = footerButtonSize,
                                iconSize = footerIconSize,
                                contentDescription = readI18n("rwpp.common.exit"),
                                onClick = { appContext.exit() }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CombatOperationsPanel(modifier: Modifier) {
        AeroPanel(modifier, borderColor = MaterialTheme.colorScheme.primary) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AeroHeader(title = "COMBAT OPERATIONS", tag = "Primary sector")
                AeroDivider()
                Combine { OperationButtons() }
            }
        }
    }

    @Composable
    private fun ExtensionPanel(modifier: Modifier) {
        AeroPanel(modifier, borderColor = MaterialTheme.colorScheme.secondary) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AeroHeader(
                    title = "SYSTEM & EXTENSIONS",
                    tag = "Data center",
                    color = MaterialTheme.colorScheme.secondary
                )
                AeroDivider()
                Combine { ExtensionButtons() }
            }
        }
    }

    @Combinable
    @Composable
    fun OperationButtons() {
        val navigator = LocalScreenNavigator.current
        AeroMenuButton(
            title = readI18n("rwpp.menu.mission"),
            subTitle = "Single player campaign".uppercase(),
            iconPainter = painterResource(Res.drawable.destruction_30),
            onClick = { navigator.push<MissionScreen>() }
        )

        AeroMenuButton(
            title = readI18n("menus.singlePlayer.skirmish"),
            subTitle = "Skirmish battle vs AI".uppercase(),
            iconPainter = painterResource(Res.drawable.swords_30),
            onClick = {
                val game = appKoin.get<Game>()
                game.hostNewSinglePlayer(false)
                navigator.push<BattleRoomScreen>()
            }
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.multiplayer"),
            subTitle = "Global network warfare".uppercase(),
            iconPainter = painterResource(Res.drawable.group_30),
            onClick = { navigator.push<MultiplayerScreen>() }
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.sandbox"),
            subTitle = "Tactical editor & simulation".uppercase(),
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
            subTitle = "Modifications".uppercase(),
            iconPainter = painterResource(Res.drawable.dns_30),
            onClick = { navigator.push<ModsScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.browser.resourceBrowser"),
            subTitle = "Community hub".uppercase(),
            iconPainter = painterResource(Res.drawable.public_30),
            onClick = { navigator.push<ResourceBrowserScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.replay"),
            subTitle = "Tactical replays".uppercase(),
            iconVector = Icons.Default.PlayArrow,
            onClick = { navigator.push<ReplaysScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.matchHistory.menu"),
            subTitle = "MATCH ARCHIVE",
            iconPainter = painterResource(Res.drawable.stacks_30),
            onClick = { navigator.push<MatchHistoryScreen>() },
            accentColor = MaterialTheme.colorScheme.secondary
        )

        AeroMenuButton(
            title = readI18n("rwpp.menu.settings"),
            subTitle = "Core configuration".uppercase(),
            iconVector = Icons.Default.Settings,
            onClick = { navigator.push<SettingsScreen>("RWPP") },
            accentColor = MaterialTheme.colorScheme.secondary
        )
    }
}
