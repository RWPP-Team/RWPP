/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.config

import io.github.rwpp.config.Settings
import kotlinx.serialization.ExperimentalSerializationApi
import net.peanuuutz.tomlkt.Toml

/** Native preferences edited by the settings screen, excluding usage, connection and account state. */
internal val EDITABLE_NATIVE_SETTINGS: Set<String> = setOf(
    "showFps", "showUnitWaypoints", "showHp", "showUnitIcons", "renderVsync",
    "highRefreshRate", "renderClouds", "shaderEffects", "slick2dFullScreen", "slick2dBorderless",
    "showSelectedUnitsList", "useMinimapAllyColors", "showWarLogOnScreen", "smartSelection_v2",
    "quickRally", "doubleClickToAttackMove", "forceEnglish", "teamUnitCapSinglePlayer",
    "teamUnitCapHostedGame", "scrollSpeed", "edgeScrollSpeed", "masterVolume", "gameVolume",
    "interfaceVolume", "musicVolume", "udpInMultiplayer", "saveMultiplayerReplays",
    "showChatAndPingShortcuts", "showMapPingsOnBattlefield", "showMapPingsOnMinimap", "showPlayerChatInGame",
)

/**
 * In-memory entry state for settings previews. Restores the injected Settings object in place;
 * never reloads files or rolls back other Config instances such as match history and play time.
 * The caller owns preview side effects and native hotkey objects, and must restore those too.
 */
internal class SettingsEditSession private constructor(
    private val settings: Settings,
    private val serializedSettings: String,
    private val nativeValues: Map<String, Any?>,
    private val readNativeSetting: (String) -> Any?,
    private val writeNativeSetting: (String, Any?) -> Unit,
) {
    private val originalSettings = decodeSettings()

    fun hasChanges(): Boolean =
        persistedFields.any { it.get(settings) != it.get(originalSettings) } ||
                nativeValues.any { (key, original) -> readNative(key, readNativeSetting) != original }

    fun restore() {
        // Decode again: collections assigned to the live object must never alias the entry snapshot.
        val restored = decodeSettings()
        persistedFields.forEach { field -> field.set(settings, field.get(restored)) }

        var failure: Exception? = null
        nativeValues.forEach { (key, original) ->
            try {
                writeNativeSetting(key, original)
            } catch (cause: Exception) {
                val problem = IllegalStateException("Could not restore native setting '$key'", cause)
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    private fun decodeSettings(): Settings = Toml.decodeFromString(Settings.serializer(), serializedSettings)

    companion object {
        /**
         * Adapters retain each native scalar's type, including nullable values. Read failures are
         * explicit; a host with unavailable preferences must select its supported keys beforehand.
         */
        fun capture(
            settings: Settings,
            readNativeSetting: (String) -> Any?,
            writeNativeSetting: (String, Any?) -> Unit,
            nativeKeys: Set<String> = EDITABLE_NATIVE_SETTINGS,
        ): SettingsEditSession {
            val serialized = Toml.encodeToString(Settings.serializer(), settings)
            val values = nativeKeys.associateWith { key -> readNative(key, readNativeSetting) }
            return SettingsEditSession(settings, serialized, values, readNativeSetting, writeNativeSetting)
        }

        @OptIn(ExperimentalSerializationApi::class)
        private val persistedFields = Settings.serializer().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map { index ->
                Settings::class.java.getDeclaredField(descriptor.getElementName(index)).apply { isAccessible = true }
            }
        }

        private fun readNative(key: String, read: (String) -> Any?): Any? {
            val value = try {
                read(key)
            } catch (cause: Exception) {
                throw IllegalStateException("Could not read native setting '$key'", cause)
            }
            require(value == null || value is Boolean || value is String || value is Char ||
                    value is Byte || value is Short || value is Int || value is Long ||
                    value is Float || value is Double) {
                "Native setting '$key' must be an immutable scalar"
            }
            return value
        }
    }
}
