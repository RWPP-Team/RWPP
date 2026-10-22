/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwml.api.Rwml
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.config.Settings
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.themes
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import org.koin.compose.koinInject
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Single
import kotlin.math.roundToInt

@Single
class SettingsScreen(
    @InjectedParam private val defaultTheme: String,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val configIO = koinInject<ConfigIO>()
        val appContext = koinInject<AppContext>()
        val settings = koinInject<Settings>()

        var backgroundImagePath by remember { mutableStateOf(settings.backgroundImagePath ?: "") }
        var selectedItem by remember { mutableIntStateOf(0) }

        val items = listOf(
            "graphics",
            "gameplay",
            "audio",
            "developer",
            "networking",
            "rwpp-client",
            "rwpp-theme"
        )

        val tabLabels = remember(items) {
            items.map { itemKey ->
                if (itemKey.startsWith("rwpp-")) {
                    readI18n("rwpp.settings.${itemKey.removePrefix("rwpp-")}")
                } else {
                    readI18n("menus.settings.heading.$itemKey")
                }
            }
        }

        AeroScaledContainer (
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            AeroPanel(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.45f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // 顶部终端标题与控制按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroHeader(
                            title = "SYSTEM CONFIGURATION MATRIX",
                            tag = "SETTINGS_v2.0",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )

                        Spacer(modifier = Modifier.width(16.dp))
                        AeroButton(
                            text = readI18n("rwpp.common.exitAndSave"),
                            isDanger = true,
                            onClick = {
                                settings.backgroundImagePath = backgroundImagePath
                                configIO.saveAllConfig()
                                UI.backgroundImagePath = backgroundImagePath
                                navigator.pop()
                            },
                            clickSound = Sounds.closePopup,
                            modifier = Modifier.width(120.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    AeroTabRow(
                        selectedTabIndex = selectedItem,
                        tabs = tabLabels,
                        onTabSelected = { selectedItem = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    AeroDivider(modifier = Modifier.padding(vertical = 10.dp))

                    val state = rememberLazyListState()

                    LazyColumnScrollbar(
                        state = state,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = selectedItem) {
                                val activeCategory = items[selectedItem]

                                when (activeCategory) {
                                    "graphics" -> AeroSettingGroup(
                                        title = readI18n("menus.settings.heading.graphics"),
                                        tag = "DISPLAY"
                                    ) {
                                        SettingsSwitchRW("showUnitWaypoints")
                                        SettingsSwitchRW("showHp", labelKey = "alwayUnitHealth")
                                        SettingsSwitchRW("showUnitIcons", labelKey = "unitIcons")
                                        SettingsSwitchRW("renderVsync")
                                        SettingsSwitchRW("renderClouds")
                                        SettingsSwitchRW("shaderEffects")
                                        SettingsSwitchRW("enableMouseCapture")
                                        SettingsSwitchRW("quickRally")
                                        SettingsSwitchRW("doubleClickToAttackMove")

                                        if (appContext.isDesktop()) {
                                            AeroSettingToggle(
                                                label = readI18n("menus.settings.option.immersiveFullScreen"),
                                                defaultValue = settings.isFullscreen,
                                                onCheckedChange = { settings.isFullscreen = it }
                                            )
                                        }
                                    }

                                    "gameplay" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AeroSettingGroup(
                                            title = readI18n("menus.settings.heading.gameplay"),
                                            tag = "MECHANICS"
                                        ) {
                                            SettingsSwitchRW("showSelectedUnitsList")
                                            SettingsSwitchRW("useMinimapAllyColors")
                                            SettingsSwitchRW("showWarLogOnScreen")
                                            SettingsSwitchRW("smartSelection_v2", labelKey = "smartSelection")

                                            AeroSettingToggle(
                                                label = readI18n("menus.settings.option.forceEnglish"),
                                                defaultValue = Rwml.get().ui().messages().language() == "en",
                                                onCheckedChange = {
                                                    settings.forceEnglish = it
                                                    configIO.setGameConfig("forceEnglish", it)
                                                    Rwml.get().ui().messages().language(if (it) "en" else "system")
                                                }
                                            )

                                            SettingsSwitchRW("showUnitGroups", labelKey = "unitGroupInterface")

                                            var xOffset by remember { mutableIntStateOf(settings.displayUnitGroupXOffset) }
                                            AeroSettingTextField(
                                                label = readI18n("rwpp.settings.displayUnitGroupXOffset"),
                                                value = xOffset.toString(),
                                                typeInOnlyInteger = true,
                                                typeInNumberOnly = true,
                                                onValueChange = {
                                                    xOffset = it.toIntOrNull() ?: 0
                                                    settings.displayUnitGroupXOffset = it.toIntOrNull() ?: 0
                                                }
                                            )

                                            var maxDisplayUnitGroupCountState by remember { mutableIntStateOf(settings.maxDisplayUnitGroupCount) }
                                            AeroSettingSlider(
                                                label = readI18n("rwpp.settings.maxDisplayUnitGroupCount"),
                                                value = maxDisplayUnitGroupCountState / 10f,
                                                onValueChange = {
                                                    val count = (it * 10).roundToInt()
                                                    maxDisplayUnitGroupCountState = count
                                                    settings.maxDisplayUnitGroupCount = count
                                                },
                                                valueFormat = { "${(it * 10).roundToInt()}" }
                                            )

                                            if (appContext.isDesktop()) {
                                                val list = remember { listOf("Default", "Software", "OpenGL") }
                                                var selectedBackendIndex by remember { mutableIntStateOf(list.indexOf(settings.renderingBackend).coerceAtLeast(0)) }

                                                AeroSettingDropDown(
                                                    label = "Rendering Backend",
                                                    items = list,
                                                    selectedIndex = selectedBackendIndex,
                                                    onItemSelected = { idx, backend ->
                                                        selectedBackendIndex = idx
                                                        settings.renderingBackend = backend
                                                    }
                                                )
                                            }

                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.displayTimeInGame"),
                                                defaultValue = settings.displayTimeInGame,
                                                onCheckedChange = { settings.displayTimeInGame = it }
                                            )

                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.enhancedReinforceTroops"),
                                                defaultValue = settings.enhancedReinforceTroops,
                                                onCheckedChange = { settings.enhancedReinforceTroops = it }
                                            )

                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.showUnitTargetLine"),
                                                defaultValue = settings.showUnitTargetLine,
                                                onCheckedChange = { settings.showUnitTargetLine = it }
                                            )

                                            val effectLimits = remember { listOf("Zero", "Keep", "Unlimited") }
                                            var effectLimitIndex by remember { mutableIntStateOf(effectLimits.indexOf(settings.effectLimitForAllEffects).coerceAtLeast(0)) }
                                            AeroSettingDropDown(
                                                label = readI18n("rwpp.settings.effectLimitForAllEffects"),
                                                items = effectLimits,
                                                selectedIndex = effectLimitIndex,
                                                onItemSelected = { idx, type ->
                                                    effectLimitIndex = idx
                                                    settings.effectLimitForAllEffects = type
                                                }
                                            )

                                            if (appContext.isAndroid()) {
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.enableVolumeKeyMapping"),
                                                    defaultValue = settings.enableVolumeKeyMapping,
                                                    onCheckedChange = { settings.enableVolumeKeyMapping = it }
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.enableLargerKeys"),
                                                    defaultValue = settings.enableLargerKeys,
                                                    onCheckedChange = { settings.enableLargerKeys = it }
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.mouseMoveView"),
                                                    defaultValue = settings.mouseMoveView,
                                                    onCheckedChange = { settings.mouseMoveView = it }
                                                )
                                            }

                                            var teamCapSingle by remember { mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapSinglePlayer")) }
                                            AeroSettingTextField(
                                                label = readI18n("rwpp.settings.teamUnitCapSinglePlayer"),
                                                value = teamCapSingle?.toString() ?: "",
                                                lengthLimitCount = 6,
                                                typeInNumberOnly = true,
                                                typeInOnlyInteger = true,
                                                onValueChange = {
                                                    teamCapSingle = it.toIntOrNull()
                                                    configIO.setGameConfig("teamUnitCapSinglePlayer", teamCapSingle ?: 100)
                                                }
                                            )

                                            var teamCapHosted by remember { mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapHostedGame")) }
                                            AeroSettingTextField(
                                                label = readI18n("rwpp.settings.teamUnitCapHostedGame"),
                                                value = teamCapHosted?.toString() ?: "",
                                                lengthLimitCount = 6,
                                                typeInNumberOnly = true,
                                                typeInOnlyInteger = true,
                                                onValueChange = {
                                                    teamCapHosted = it.toIntOrNull()
                                                    configIO.setGameConfig("teamUnitCapHostedGame", teamCapHosted ?: 100)
                                                }
                                            )

                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.showExtraButton"),
                                                defaultValue = settings.showExtraButton,
                                                onCheckedChange = { settings.showExtraButton = it }
                                            )
                                        }

                                        AeroSettingGroup(
                                            title = readI18n("rwpp.settings.buildings"),
                                            tag = "STRUCTURES"
                                        ) {
                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.showAttackRange"),
                                                defaultValue = settings.showBuildingAttackRange,
                                                onCheckedChange = { settings.showBuildingAttackRange = it }
                                            )
                                        }

                                        AeroSettingGroup(
                                            title = readI18n("rwpp.settings.units"),
                                            tag = "SQUAD"
                                        ) {
                                            val attackRangeTypes = Settings.unitAttackRangeTypes
                                            var rangeTypeIndex by remember { mutableIntStateOf(attackRangeTypes.indexOf(settings.showAttackRangeUnit).coerceAtLeast(0)) }
                                            AeroSettingDropDown(
                                                label = readI18n("rwpp.settings.showAttackRange"),
                                                items = attackRangeTypes,
                                                selectedIndex = rangeTypeIndex,
                                                onItemSelected = { idx, type ->
                                                    rangeTypeIndex = idx
                                                    settings.showAttackRangeUnit = type
                                                }
                                            )
                                        }

                                        AeroSettingGroup(
                                            title = readI18n("rwpp.settings.inGameOffscreenPanel"),
                                            tag = "HUD_PANEL"
                                        ) {
                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.enableQuickSelectMenu"),
                                                defaultValue = settings.enableQuickSelectMenu,
                                                onCheckedChange = { settings.enableQuickSelectMenu = it }
                                            )
                                        }
                                    }

                                    "audio" -> AeroSettingGroup(
                                        title = readI18n("menus.settings.heading.audio"),
                                        tag = "ACOUSTICS"
                                    ) {
                                        SettingsSliderRW("masterVolume")
                                        SettingsSliderRW("gameVolume")
                                        SettingsSliderRW("interfaceVolume")
                                        SettingsSliderRW("musicVolume")
                                    }

                                    "developer" -> AeroSettingGroup(
                                        title = readI18n("menus.settings.heading.developer"),
                                        tag = "DEV_TOOLS"
                                    ) {
                                        SettingsSwitchRW("showFps")
                                        AeroSettingToggle(
                                            "Show Welcome Message",
                                            defaultValue = settings.showWelcomeMessage ?: false
                                        ) { settings.showWelcomeMessage = it }
                                    }

                                    "networking" -> AeroSettingGroup(
                                        title = readI18n("menus.settings.heading.networking"),
                                        tag = "NET_PROTOCOL"
                                    ) {
                                        SettingsSwitchRW("udpInMultiplayer")
                                        SettingsSwitchRW("saveMultiplayerReplays", "saveReplays")
                                        SettingsSwitchRW("showChatAndPingShortcuts")
                                        SettingsSwitchRW("showMapPingsOnBattlefield")
                                        SettingsSwitchRW("showMapPingsOnMinimap")
                                        SettingsSwitchRW("showPlayerChatInGame")
                                    }

                                    "rwpp-client" -> AeroSettingGroup(
                                        title = readI18n("rwpp.settings.client"),
                                        tag = "RWPP_CORE"
                                    ) {
                                        // RWPP 专属客户端选项拓展槽
                                    }

                                    "rwpp-theme" -> {
                                        val themeList = remember { themes.keys.toList() }
                                        var themeIndex by remember { mutableIntStateOf(themeList.indexOf(defaultTheme).coerceAtLeast(0)) }
                                        val appContext = koinInject<AppContext>()

                                        AeroSettingGroup(
                                            title = readI18n("rwpp.settings.theme"),
                                            tag = "AESTHETICS"
                                        ) {
                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.enableAnimations"),
                                                defaultValue = settings.enableAnimations,
                                                onCheckedChange = { settings.enableAnimations = it }
                                            )
                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.boldText"),
                                                defaultValue = settings.boldText,
                                                onCheckedChange = { settings.boldText = it }
                                            )
                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.changeGameTheme"),
                                                defaultValue = settings.changeGameTheme,
                                                onCheckedChange = { settings.changeGameTheme = it }
                                            )
                                            AeroSettingDropDown(
                                                label = "ColorScheme",
                                                items = themeList,
                                                selectedIndex = themeIndex,
                                                onItemSelected = { idx, theme ->
                                                    UI.selectedColorSchemeName = theme
                                                    themeIndex = idx
                                                }
                                            )
                                            AeroSettingTextField(
                                                label = readI18n("rwpp.settings.setBackgroundImagePath"),
                                                value = backgroundImagePath,
                                                onValueChange = { backgroundImagePath = it },
                                                trailingIcon = {
                                                    AeroIconButton(
                                                        rememberVectorPainter(Icons.AutoMirrored.Filled.List),
                                                        contentDescription = null,
                                                        size = 24.dp,
                                                        onClick = {
                                                            appContext.openFileChooser { backgroundImagePath = it.canonicalPath }
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRW(
    name: String,
    labelKey: String = name,
    labelName: String = readI18n("menus.settings.option.$labelKey")
) {
    val configIO = koinInject<ConfigIO>()
    val defaultValue = remember { configIO.getGameConfig<Boolean>(name) }

    AeroSettingToggle(
        label = labelName,
        defaultValue = defaultValue,
        onCheckedChange = { newValue ->
            configIO.setGameConfig(name, newValue)
        }
    )
}

@Composable
private fun SettingsSliderRW(
    name: String,
    labelKey: String = name,
    labelName: String = readI18n("menus.settings.option.$labelKey")
) {
    val configIO = koinInject<ConfigIO>()
    var value by remember { mutableFloatStateOf(configIO.getGameConfig(name)) }

    AeroSettingSlider(
        label = labelName,
        value = value,
        onValueChange = { newValue ->
            value = newValue
            configIO.setGameConfig(name, newValue)
        },
        valueFormat = { "%.0f%%".format(it * 100) }
    )
}
