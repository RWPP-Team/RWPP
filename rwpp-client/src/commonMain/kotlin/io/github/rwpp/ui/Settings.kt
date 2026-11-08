/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import io.github.rwml.api.Rwml
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.WindowManager
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
    /** Keep the user's category when opening settings again. */
    var selectedItem by mutableIntStateOf(0)

    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val configIO = koinInject<ConfigIO>()
        val appContext = koinInject<AppContext>()
        val settings = koinInject<Settings>()
        val compact = LocalWindowManager.current != WindowManager.Large

        var backgroundImagePath by remember { mutableStateOf(settings.backgroundImagePath ?: "") }
        // The opacity state resolves its default before Koin exists in some hosts.
        LaunchedEffect(Unit) { HudOpacity.syncFrom(settings) }
        var savedOnExit by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            onDispose {
                settings.backgroundImagePath = backgroundImagePath
                UI.backgroundImagePath = backgroundImagePath
                if (!savedOnExit) runCatching { configIO.saveAllConfig() }.onFailure {
                    io.github.rwpp.api.logging.logger().error("Could not save configuration while closing settings", it)
                }
            }
        }

        val items = listOf(
            "graphics",
            "appearance",
            "gameplay",
            "hotkeys",
            "audio",
            "networking",
            "developer",
            "rwpp-client",
            "rwpp-theme"
        )

        val tabLabels = remember(items) {
            items.map { itemKey ->
                if (itemKey == "hotkeys") readI18n("rwpp.hotkeys.title")
                else if (itemKey == "appearance") readI18n("rwpp.appearance.title")
                else if (itemKey.startsWith("rwpp-")) {
                    readI18n("rwpp.settings.${itemKey.removePrefix("rwpp-")}")
                } else {
                    readI18n("menus.settings.heading.$itemKey")
                }
            }
        }

        AeroScaledContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            AeroPanel(
                modifier = Modifier.fillMaxHeight().widthIn(max = 1160.dp).fillMaxWidth(0.96f),
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
                            title = "SETTINGS",
                            tag = if (compact) "CONFIG" else "SYSTEM CONFIGURATION",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )

                        Spacer(modifier = Modifier.width(if (compact) 8.dp else 16.dp))
                        AeroButton(
                            text = readI18n("rwpp.common.exitAndSave"),
                            isDanger = true,
                            onClick = {
                                settings.backgroundImagePath = backgroundImagePath
                                runCatching { configIO.saveAllConfig() }.onSuccess {
                                    savedOnExit = true
                                    UI.backgroundImagePath = backgroundImagePath
                                    navigator.pop()
                                }.onFailure {
                                    io.github.rwpp.api.logging.logger().error("Could not save configuration", it)
                                    UI.showWarning(readI18n("rwpp.settings.saveFailed", it.message.orEmpty()))
                                }
                            },
                            clickSound = Sounds.closePopup,
                            modifier = Modifier.width(120.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val activeItem = selectedItem.coerceIn(items.indices)
                    AeroTabRow(
                        selectedTabIndex = activeItem,
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
                            item(key = activeItem) {
                                val activeCategory = items[activeItem]

                                when (activeCategory) {
                                    "appearance" -> UnitAppearancePreferences(settings)
                                    "hotkeys" -> HotkeySettings(settings)
                                    "graphics" -> AeroSettingGroup(
                                        title = "GRAPHICS",
                                        tag = "DISPLAY"
                                    ) {
                                        AeroSettingSlider(
                                            label = readI18n("rwpp.settings.globalUiScale"),
                                            value = settings.globalUiScale.coerceIn(0.6f, 1.5f),
                                            onValueChange = { value ->
                                                val scale = (value * 100f).roundToInt() / 100f
                                                settings.globalUiScale = scale
                                                AeroUiScalePreference.multiplier = scale
                                            },
                                            valueRange = 0.6f..1.5f,
                                            valueFormat = { "${(it * 100f).roundToInt()}%" },
                                        )
                                        AeroSettingToggle(
                                            readI18n("rwpp.settings.smoothGameTextures"),
                                            settings.smoothGameTextures,
                                            onCheckedChange = { settings.smoothGameTextures = it })
                                        AeroSettingToggle(
                                            readI18n("rwpp.settings.removeDrawLimit"),
                                            settings.removeDrawLimit,
                                            onCheckedChange = { settings.removeDrawLimit = it })
                                        SettingsSwitchRW("showFps")
                                        GameVisualPreferences(settings)
                                        AeroSettingToggle(
                                            readI18n("rwpp.settings.enhancedWorldRendering"),
                                            settings.enhancedWorldRendering,
                                            onCheckedChange = { settings.enhancedWorldRendering = it })
                                        if (appContext.isDesktop()) {
                                            CommunityDlss5Settings(settings)
                                            AeroSettingGroup(
                                                title = "SCREEN-SPACE LIGHTING",
                                                tag = "SSR / RT",
                                            ) {
                                                Text(
                                                    readI18n("rwpp.settings.enhancedWorldLightingHint"),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.waterWetGroundReflections"),
                                                    defaultValue = settings.worldScreenSpaceReflections,
                                                    subLabel = readI18n("rwpp.settings.worldImageReflectionsHint"),
                                                    onCheckedChange = { settings.worldScreenSpaceReflections = it },
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.screenSpaceRayTracing"),
                                                    defaultValue = settings.worldScreenSpaceRayTracing,
                                                    subLabel = readI18n("rwpp.settings.screenSpaceRayTracingHint"),
                                                    onCheckedChange = { settings.worldScreenSpaceRayTracing = it },
                                                )
                                                var rayTraceSteps by remember {
                                                    mutableIntStateOf(
                                                        settings.worldRayTraceSteps.coerceIn(
                                                            2,
                                                            8
                                                        )
                                                    )
                                                }
                                                AeroSettingSlider(
                                                    label = readI18n("rwpp.settings.reflectionRaySteps"),
                                                    value = rayTraceSteps / 8f,
                                                    onValueChange = {
                                                        rayTraceSteps = ((it * 4f).roundToInt() * 2).coerceIn(2, 8)
                                                        settings.worldRayTraceSteps = rayTraceSteps
                                                    },
                                                    valueRange = 0.25f..1f,
                                                    valueFormat = { "${((it * 4f).roundToInt() * 2).coerceIn(2, 8)}" },
                                                )
                                                Text(
                                                    readI18n("rwpp.settings.worldLightingDescription"),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.groundWetSurfaceHighlights"),
                                                    defaultValue = settings.worldSurfaceHighlights,
                                                    subLabel = readI18n("rwpp.settings.originalSpriteShadingHint"),
                                                    onCheckedChange = { settings.worldSurfaceHighlights = it },
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.sceneHighlightBloom"),
                                                    defaultValue = settings.worldDynamicBloom,
                                                    onCheckedChange = { settings.worldDynamicBloom = it },
                                                )
                                                var wetStrength by remember {
                                                    mutableFloatStateOf(
                                                        settings.worldWetGroundStrength.coerceIn(
                                                            0f,
                                                            2f
                                                        )
                                                    )
                                                }
                                                AeroSettingSlider(
                                                    label = readI18n("rwpp.settings.wetGroundReflectionStrength"),
                                                    value = wetStrength,
                                                    onValueChange = {
                                                        wetStrength =
                                                            it.coerceIn(0f, 2f); settings.worldWetGroundStrength =
                                                        wetStrength
                                                    },
                                                    valueRange = 0f..2f,
                                                    valueFormat = { "${(it * 100f).roundToInt()}%" },
                                                )
                                                var lightingStrength by remember {
                                                    mutableFloatStateOf(
                                                        settings.worldLightingStrength.coerceIn(
                                                            0f,
                                                            3f
                                                        )
                                                    )
                                                }
                                                var waveStrength by remember {
                                                    mutableFloatStateOf(
                                                        settings.worldWaterWaveStrength.coerceIn(
                                                            0f,
                                                            1.5f
                                                        )
                                                    )
                                                }
                                                AeroSettingSlider(
                                                    label = readI18n("rwpp.settings.waterWaveStrength"),
                                                    value = waveStrength,
                                                    onValueChange = {
                                                        waveStrength = it.coerceIn(0f, 1.5f)
                                                        settings.worldWaterWaveStrength = waveStrength
                                                    },
                                                    valueRange = 0f..1.5f,
                                                    valueFormat = { "${(it * 100f).roundToInt()}%" },
                                                )
                                                AeroSettingSlider(
                                                    label = readI18n("rwpp.settings.sunlightHighlightIntensity"),
                                                    value = lightingStrength,
                                                    onValueChange = {
                                                        lightingStrength = it.coerceIn(0f, 3f)
                                                        settings.worldLightingStrength = lightingStrength
                                                    },
                                                    valueRange = 0f..3f,
                                                    valueFormat = { "${(it * 100f).roundToInt()}%" },
                                                )
                                            }
                                        }
                                        SettingsSwitchRW("showUnitWaypoints")
                                        SettingsSwitchRW("showHp", labelKey = "alwayUnitHealth")
                                        SettingsSwitchRW("showUnitIcons", labelKey = "unitIcons")
                                        SettingsSwitchRW("renderVsync")
                                        SettingsSwitchRW("highRefreshRate")
                                        SettingsSwitchRW("renderClouds")
                                        SettingsSwitchRW("shaderEffects")

                                        if (appContext.isDesktop()) {
                                            AeroSettingToggle(
                                                label = readI18n("menus.settings.option.immersiveFullScreen"),
                                                defaultValue = configIO.getGameConfig<Boolean>("slick2dFullScreen"),
                                                subLabel = readI18n("rwpp.settings.immersiveFullScreenHint"),
                                                onCheckedChange = {
                                                    settings.isFullscreen = it
                                                    configIO.setGameConfig("slick2dFullScreen", it)
                                                    // applyResolution resolves borderless first
                                                    // (`if (borderless) fullscreen = true`). Keep the
                                                    // runtime field in step so a stale value cannot
                                                    // override the switch before the next start.
                                                    configIO.setGameConfig("slick2dBorderless", it)
                                                    // The native display mode is chosen at startup, so
                                                    // persist immediately instead of relying on the
                                                    // exit-time save.
                                                    runCatching {
                                                        com.corrodinggames.rts.gameFramework.GameEngine
                                                            .getInstance().settings.save()
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    "gameplay" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AeroSettingGroup(
                                            title = "GAMEPLAY",
                                            tag = "MECHANICS"
                                        ) {
                                            SettingsSwitchRW("showSelectedUnitsList")
                                            SettingsSwitchRW("useMinimapAllyColors")
                                            SettingsSwitchRW("showWarLogOnScreen")
                                            SettingsSwitchRW("smartSelection_v2", labelKey = "smartSelection")
                                            SettingsSwitchRW("quickRally")
                                            SettingsSwitchRW("doubleClickToAttackMove")
                                            AeroSettingToggle(
                                                readI18n("rwpp.graphics.terrainEdgeBuilding"),
                                                settings.terrainEdgeBuilding,
                                                onCheckedChange = { settings.terrainEdgeBuilding = it },
                                            )

                                            AeroSettingToggle(
                                                label = readI18n("menus.settings.option.forceEnglish"),
                                                defaultValue = Rwml.get().ui().messages().language() == "en",
                                                onCheckedChange = {
                                                    settings.forceEnglish = it
                                                    configIO.setGameConfig("forceEnglish", it)
                                                    Rwml.get().ui().messages().language(if (it) "en" else "system")
                                                }
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

                                            val effectLimitValues = remember { listOf("Zero", "Keep", "Unlimited") }
                                            val effectLimitLabels = listOf(
                                                readI18n("rwpp.settings.effectLimit.zero"),
                                                readI18n("rwpp.settings.effectLimit.keep"),
                                                readI18n("rwpp.settings.effectLimit.unlimited"),
                                            )
                                            var effectLimitIndex by remember {
                                                mutableIntStateOf(
                                                    effectLimitValues.indexOf(
                                                        settings.effectLimitForAllEffects
                                                    ).coerceAtLeast(0)
                                                )
                                            }
                                            AeroSettingDropDown(
                                                label = readI18n("rwpp.settings.effectLimitForAllEffects"),
                                                items = effectLimitLabels,
                                                selectedIndex = effectLimitIndex,
                                                onItemSelected = { idx, _ ->
                                                    effectLimitIndex = idx
                                                    settings.effectLimitForAllEffects = effectLimitValues[idx]
                                                }
                                            )

                                            if (appContext.isAndroid()) {
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.enableVolumeKeyMapping"),
                                                    defaultValue = settings.enableVolumeKeyMapping,
                                                    onCheckedChange = { settings.enableVolumeKeyMapping = it }
                                                )
                                                AeroSettingToggle(
                                                    label = readI18n("rwpp.settings.mouseMoveView"),
                                                    defaultValue = settings.mouseMoveView,
                                                    onCheckedChange = { settings.mouseMoveView = it }
                                                )
                                            }

                                            var teamCapSingle by remember {
                                                mutableStateOf(
                                                    configIO.getGameConfig<Int?>(
                                                        "teamUnitCapSinglePlayer"
                                                    )
                                                )
                                            }
                                            AeroSettingTextField(
                                                label = readI18n("rwpp.settings.teamUnitCapSinglePlayer"),
                                                value = teamCapSingle?.toString() ?: "",
                                                lengthLimitCount = 6,
                                                typeInNumberOnly = true,
                                                typeInOnlyInteger = true,
                                                onValueChange = {
                                                    teamCapSingle = it.toIntOrNull()
                                                    configIO.setGameConfig(
                                                        "teamUnitCapSinglePlayer",
                                                        teamCapSingle ?: 100
                                                    )
                                                }
                                            )

                                            var teamCapHosted by remember {
                                                mutableStateOf(
                                                    configIO.getGameConfig<Int?>(
                                                        "teamUnitCapHostedGame"
                                                    )
                                                )
                                            }
                                            AeroSettingTextField(
                                                label = readI18n("rwpp.settings.teamUnitCapHostedGame"),
                                                value = teamCapHosted?.toString() ?: "",
                                                lengthLimitCount = 6,
                                                typeInNumberOnly = true,
                                                typeInOnlyInteger = true,
                                                onValueChange = {
                                                    teamCapHosted = it.toIntOrNull()
                                                    configIO.setGameConfig(
                                                        "teamUnitCapHostedGame",
                                                        teamCapHosted ?: 100
                                                    )
                                                }
                                            )

                                        }

                                        AeroSettingGroup(
                                            title = "SMART SELECTION",
                                            tag = "OPTIONAL"
                                        ) {
                                            SmartSelectionSettings(settings)
                                        }

                                        AeroSettingGroup(
                                            title = "CAMERA",
                                            tag = "CAMERA"
                                        ) {
                                            var cameraMargin by remember { mutableFloatStateOf(settings.cameraOverscrollPixels) }
                                            AeroSettingToggle(
                                                label = readI18n("rwpp.settings.allowCameraBeyondMap"),
                                                defaultValue = settings.allowCameraOverscroll,
                                                onCheckedChange = { settings.allowCameraOverscroll = it }
                                            )
                                            AeroSettingSlider(
                                                value = cameraMargin,
                                                onValueChange = {
                                                    cameraMargin = it; settings.cameraOverscrollPixels = it
                                                },
                                                label = readI18n("rwpp.settings.cameraOverscrollPixels"),
                                                valueRange = 0f..800f,
                                                valueFormat = {
                                                    readI18n(
                                                        "rwpp.settings.cameraOverscrollValue",
                                                        it.toInt()
                                                    )
                                                }
                                            )
                                            var cameraDragSpeed by remember {
                                                mutableFloatStateOf(
                                                    runCatching { configIO.getGameConfig<Float>("scrollSpeed") }
                                                        .getOrDefault(1f).coerceIn(0.2f, 3f)
                                                )
                                            }
                                            var cameraEdgeSpeed by remember {
                                                mutableFloatStateOf(
                                                    runCatching { configIO.getGameConfig<Float>("edgeScrollSpeed") }
                                                        .getOrDefault(1f).coerceIn(0.2f, 3f)
                                                )
                                            }
                                            AeroSettingSlider(
                                                value = cameraDragSpeed,
                                                onValueChange = {
                                                    cameraDragSpeed = it
                                                    runCatching { configIO.setGameConfig("scrollSpeed", it) }
                                                },
                                                label = readI18n("rwpp.settings.cameraDragSpeed"),
                                                valueRange = 0.2f..3f,
                                                valueFormat = { "${(it * 100f).toInt()}%" }
                                            )
                                            AeroSettingSlider(
                                                value = cameraEdgeSpeed,
                                                onValueChange = {
                                                    cameraEdgeSpeed = it
                                                    runCatching { configIO.setGameConfig("edgeScrollSpeed", it) }
                                                },
                                                label = readI18n("rwpp.settings.cameraEdgeSpeed"),
                                                valueRange = 0.2f..3f,
                                                valueFormat = { "${(it * 100f).toInt()}%" }
                                            )
                                        }
                                    }

                                    "audio" -> AeroSettingGroup(
                                        title = "AUDIO",
                                        tag = "ACOUSTICS"
                                    ) {
                                        SettingsSliderRW("masterVolume")
                                        SettingsSliderRW("gameVolume")
                                        SettingsSliderRW("interfaceVolume")
                                        SettingsSliderRW("musicVolume")
                                        MusicPreferences(settings)
                                    }

                                    "networking" -> AeroSettingGroup(
                                        title = "NETWORKING",
                                        tag = "NET_PROTOCOL"
                                    ) {
                                        SettingsSwitchRW("udpInMultiplayer")
                                        SettingsSwitchRW("saveMultiplayerReplays", "saveReplays")
                                        SettingsSwitchRW("showChatAndPingShortcuts")
                                        SettingsSwitchRW("showMapPingsOnBattlefield")
                                        SettingsSwitchRW("showMapPingsOnMinimap")
                                        SettingsSwitchRW("showPlayerChatInGame")
                                    }

                                    "developer" -> AeroSettingGroup(title = "DEVELOPER", tag = "DIAGNOSTICS") {
                                        AeroSettingToggle(
                                            readI18n("rwpp.debug.collision"), settings.showCollisionBoxes,
                                            onCheckedChange = { settings.showCollisionBoxes = it })
                                        AeroSettingToggle(
                                            readI18n("rwpp.debug.performance"), settings.showPerformanceUsage,
                                            onCheckedChange = { settings.showPerformanceUsage = it })
                                    }

                                    "rwpp-client" -> AeroSettingGroup(
                                        title = "CLIENT",
                                        tag = "RWPP_CORE"
                                    ) {
                                        AeroSettingToggle(
                                            label = readI18n("rwpp.settings.autoCheckUpdate"),
                                            defaultValue = settings.autoCheckUpdate,
                                            onCheckedChange = { settings.autoCheckUpdate = it }
                                        )
                                        AeroSettingToggle(
                                            label = readI18n("rwpp.settings.showWelcomeMessage"),
                                            defaultValue = settings.showWelcomeMessage ?: false,
                                            onCheckedChange = { settings.showWelcomeMessage = it }
                                        )
                                        ClientHudSettings(settings)
                                    }

                                    "rwpp-theme" -> {
                                        val themeList = remember { themes.keys.toList() }
                                        var themeIndex by remember {
                                            mutableIntStateOf(
                                                themeList.indexOf(defaultTheme).coerceAtLeast(0)
                                            )
                                        }
                                        val appContext = koinInject<AppContext>()

                                        AeroSettingGroup(
                                            title = "THEME",
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
                                                label = readI18n("rwpp.settings.colorScheme"),
                                                items = themeList,
                                                selectedIndex = themeIndex,
                                                onItemSelected = { idx, theme ->
                                                    UI.selectedColorSchemeName = theme
                                                    settings.selectedTheme = theme
                                                    themeIndex = idx
                                                }
                                            )
                                            AeroSettingSlider(
                                                label = readI18n("rwpp.settings.backgroundTransparency"),
                                                value = HudOpacity.value,
                                                onValueChange = {
                                                    val alpha = (it * 100f).roundToInt() / 100f
                                                    HudOpacity.value = alpha
                                                    settings.backgroundTransparency = alpha
                                                },
                                                valueRange = 0f..1f,
                                                valueFormat = { "${(it * 100f).roundToInt()}%" },
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
                                                            appContext.openFileChooser {
                                                                backgroundImagePath = it.canonicalPath
                                                            }
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

@Composable
private fun SmartSelectionSettings(settings: Settings) {
    SmartSelectionExtraBuilderTypesButton(settings)

    var builderAssistanceEnabled by remember { mutableStateOf(settings.builderQueueProtectionEnabled) }
    AeroSettingToggle(
        label = readI18n("rwpp.settings.builderAssistance.enabled"),
        defaultValue = builderAssistanceEnabled,
        onCheckedChange = {
            builderAssistanceEnabled = it
            settings.builderQueueProtectionEnabled = it
        }
    )
    Text(
        text = readI18n("rwpp.settings.builderAssistance.help"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
