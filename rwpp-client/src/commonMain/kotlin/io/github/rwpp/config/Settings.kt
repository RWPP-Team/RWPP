/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.config


import io.github.rwpp.api.config.Config
import io.github.rwpp.game.building.BuildingPlanningBlueprintItem
import io.github.rwpp.logic.selection.DEFAULT_SMART_SELECTION_EXTRA_BUILDER_TYPES

import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

@Single
@Serializable
data class Settings(
    var hudAnimations: Boolean = true,
    /** Compose refresh cadence; the native game frame limit remains independent. */
    var hudRefreshRate: Int = 60,
    var aeroHoverEffects: Boolean = true,
    var aeroGuardEffects: Boolean = true,
    var aeroWaypointEffects: Boolean = true,
    var aeroBuildEffects: Boolean = true,
    var aeroRangeEffects: Boolean = true,
    var aeroEnemyRangesOnly: Boolean = false,
    var aeroRallyPointEffects: Boolean = false,
    var aeroProductionLabels: Boolean = false,
    var aeroAlliedProductionLabels: Boolean = true,
    var aeroSelection: Boolean = false,
    /** Unit type IDs treated as builders only by native mixed-combat drag smart-selection. */
    var smartSelectionExtraBuilders: String = DEFAULT_SMART_SELECTION_EXTRA_BUILDER_TYPES,
    /** Opt in to native repair orders that add long-idle selected builders to group construction. */
    var builderQueueProtectionEnabled: Boolean = true,
    var aeroUnitGlowHighlight: Boolean = false,
    var aeroDamageAnimation: Boolean = true,
    var aeroDamageNumbers: Boolean = false,
    var smoothGameTextures: Boolean = true,
    var removeDrawLimit: Boolean = false,
    /** Zero keeps the native frame limit. */
    @Volatile var gameFrameLimit: Int = 0,
    @Volatile var customZoomLimits: Boolean = false,
    @Volatile var minimumCameraZoom: Float = 0.2f,
    @Volatile var maximumCameraZoom: Float = 4.6f,
    @Volatile var unitOpacity: Float = 1f,
    @Volatile var projectileOpacity: Float = 1f,
    @Volatile var drawUnitShadows: Boolean = true,
    @Volatile var suppressDeathSmoke: Boolean = false,
    @Volatile var terrainEdgeBuilding: Boolean = true,
    @Volatile var overflowProductionQueue: Boolean = false,
    var savedBlueprints: List<SavedBlueprint> = emptyList(),
    /** One-time migration version so legacy default-false configs become enabled exactly once. */
    var terrainEdgeBuildingPreferenceVersion: Int = 0,
    @Volatile var sandboxForceBuild: Boolean = false,
    @Volatile var playMusicToEnd: Boolean = false,
    @Volatile var showCollisionBoxes: Boolean = false,
    @Volatile var showPerformanceUsage: Boolean = false,
    var enhancedWorldRendering: Boolean = false,
    var worldScreenSpaceReflections: Boolean = true,
    var worldScreenSpaceRayTracing: Boolean = true,
    var worldRayTraceSteps: Int = 6,
    var worldLightingStrength: Float = 1f,
    var worldWaterWaveStrength: Float = 1f,
    var worldSurfaceHighlights: Boolean = true,
    var worldDynamicBloom: Boolean = true,
    var worldWetGroundStrength: Float = 1f,
    /** User-supplied community controller; never loaded or launched automatically. */
    var communityDlss5Directory: String = "",
    /** User multiplier applied after AeroScaledContainer fits the UI to the current window size. */
    var globalUiScale: Float = 1f,
    var aeroOrderEffects: Boolean = false,
    var aeroOrderClickEffects: Boolean = false,
    var aeroGroupOrders: Boolean = true,
    /** Mod-defined resources render inside the status readout; this hides the rows. */
    var hudCustomResources: Boolean = true,
    /** Mod resource rows relative to the credit readout: "above" or "below". */
    var hudCustomResourcesPosition: String = "below",
    /** Mod resource rows arrangement: "vertical" or "horizontal". */
    var hudCustomResourcesLayout: String = "vertical",
    var hudChatShowTeam: Boolean = false,
    var hudChatShowTimestamp: Boolean = false,
    /** Use each player's configured team color for unit markers on the Aero minimap. */
    var minimapUsePlayerColors: Boolean = false,
    /** Reassign native team-mask colors locally in the renderer; never changes Team state. */
    @Volatile var unitAppearancePaintEnabled: Boolean = false,
    /** Ten comma-separated 1-based team slots or hex colors, ordered by the unit owner's slot. */
    @Volatile var unitAppearancePlayerColorMap: String = "1,2,3,4,5,6,7,8,9,10",
    /** When enabled, use the relation color source instead of the per-slot mapping. */
    @Volatile var unitAppearanceTriColorEnabled: Boolean = false,
    /** Each relation source accepts `position`, 1..10, or #RRGGBB/#AARRGGBB. */
    @Volatile var unitAppearanceSelfColor: String = "position",
    @Volatile var unitAppearanceEnemyColor: String = "position",
    @Volatile var unitAppearanceAllyColor: String = "position",
    /** Independently recolor native selection rings using the configured relation colors. */
    @Volatile var unitAppearanceSelectionRingsEnabled: Boolean = false,
    @Volatile var unitAppearanceRingSelfColor: String = "#B400FF00",
    @Volatile var unitAppearanceRingEnemyColor: String = "#B4FF0000",
    @Volatile var unitAppearanceRingAllyColor: String = "#B4FFFF00",
    @Volatile var unitAppearanceRingNeutralColor: String = "#B45A5A5A",
    /** Draw a bright, paint-matched silhouette edge around units. */
    @Volatile var unitAppearanceOutlineBrighteningEnabled: Boolean = false,
    var hideOriginalHud: Boolean = true,
    var hotkeyBindings: Map<String, String> = emptyMap(),
    /** 0 follows native settings, 1 left button orders, 2 right button orders. */
    var mouseOrderButton: Int = 0,
    var mouseBuildConfirmButton: Int = 0,
    var actionConfirmButtons: Map<String, Int> = emptyMap(),
    /** One-time migration for RWPP hotkey defaults that changed after first release. */
    var hotkeyBindingsMigrationVersion: Int = 0,
    /** Match detail retention in days; zero disables automatic expiry. */
    var matchHistoryRetentionDays: Int = 30,
    var showWelcomeMessage: Boolean? = null,
    var roomPresets: List<RoomPreset> = emptyList(),
    var ignoreVersion: String? = null,
    var autoCheckUpdate: Boolean = true,
    var enhancedReinforceTroops: Boolean = false,
    var backgroundImagePath: String? = null,
    var selectedTheme: String? = null,
    /** Opacity of HUD panels and their controls; 0.7 matches the designed panel alpha. */
    var backgroundTransparency: Float = 0.7f,
    var backgroundImageTransparency: Float = 1f,
    var showBuildingAttackRange: Boolean = false,
    /** Share one range color and merged contour across all players on the same allied team. */
    var aeroRangeUseTeamColors: Boolean = true,
    var showExtraButton: Boolean = false,
    /** @see unitAttackRangeTypes */
    var showAttackRangeUnit: String = "Never",
    /** Master switch for interface motion: buttons, lists, screen flashes and dialog panels. */
    var enableAnimations: Boolean = true,
    var maxDisplayUnitGroupCount: Int = 7,
    var displayUnitGroupXOffset: Int = 0,
    var changeGameTheme: Boolean = false,
    var showUnitTargetLine: Boolean = false,
    var improvedHealthBar: Boolean = false,
    var mouseMoveView: Boolean = false,
    var allowCameraOverscroll: Boolean = false,
    var cameraOverscrollPixels: Float = 160f,
    // var pathfindingOptimization: Boolean = false,
    var boldText: Boolean = false,
    var forceEnglish: Boolean = false,

    @Deprecated("Useless")
    var enableOffscreenPanel: Boolean = false,

    var displayTimeInGame: Boolean = false,
    var effectLimitForAllEffects: String = "Keep", // Zero, Keep, Unlimited

    // --- Android ---
    var enableVolumeKeyMapping: Boolean = false,
    var enableLargerKeys: Boolean = false,
    // ---------------

    // --- Desktop ---
    var renderingBackend: String = "OpenGL", // Default, Software, OpenGL
    /**
     * Decide whether to allow game in full screen (Only PC)
     */
    var isFullscreen: Boolean = false,
) : Config {
    companion object {
        val unitAttackRangeTypes = listOf("Never", "Land", "Air", "All")
    }
}

@Serializable
data class SavedBlueprint(val name: String, val items: List<BuildingPlanningBlueprintItem>)
