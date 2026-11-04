/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.hotkeys

import io.github.rwpp.api.i18n.readI18n
import java.util.*

private val hotkeyActionTranslationKeys = mapOf(
    "camera up" to "rwpp.hotkeys.action.cameraUp",
    "camera down" to "rwpp.hotkeys.action.cameraDown",
    "camera left" to "rwpp.hotkeys.action.cameraLeft",
    "camera right" to "rwpp.hotkeys.action.cameraRight",
    "zoom in" to "rwpp.hotkeys.action.zoomIn",
    "zoom out" to "rwpp.hotkeys.action.zoomOut",
    "send chat" to "rwpp.hotkeys.action.sendChat",
    "send team chat" to "rwpp.hotkeys.action.sendTeamChat",
    "ping map" to "rwpp.hotkeys.action.pingMap",
    "show menu" to "rwpp.hotkeys.action.showMenu",
    "save game" to "rwpp.hotkeys.action.saveGame",
    "deselect units" to "rwpp.hotkeys.action.deselectUnits",
    "go to notification" to "rwpp.hotkeys.action.goToNotification",
    "select whole army" to "rwpp.hotkeys.action.selectWholeArmy",
    "select command center" to "rwpp.hotkeys.action.selectCommandCenter",
    "cycle builders" to "rwpp.hotkeys.action.cycleBuilders",
    "cycle extractors" to "rwpp.hotkeys.action.cycleExtractors",
    "cycle upgradable fabricators" to "rwpp.hotkeys.action.cycleUpgradableFabricators",
    "cycle land factories" to "rwpp.hotkeys.action.cycleLandFactories",
    "cycle air factories" to "rwpp.hotkeys.action.cycleAirFactories",
    "next music track" to "rwpp.hotkeys.action.nextMusicTrack",
    "pause game" to "rwpp.hotkeys.action.pauseGame",
    "slower" to "rwpp.hotkeys.action.slower",
    "faster" to "rwpp.hotkeys.action.faster",
    "attack move" to "rwpp.hotkeys.action.attackMove",
    "stop" to "rwpp.hotkeys.action.stop",
    "guard unit" to "rwpp.hotkeys.action.guardUnit",
    "patrol" to "rwpp.hotkeys.action.patrol",
    "reclaim" to "rwpp.hotkeys.action.reclaim",
    "action - upgrade" to "rwpp.hotkeys.action.upgrade",
    "action - set rally" to "rwpp.hotkeys.action.setRally",
    "rwpp same type" to "rwpp.hotkeys.action.rwppSameType",
    "rwpp follow selection" to "rwpp.hotkeys.action.rwppFollowSelection",
    "rwpp low health" to "rwpp.tactical.lowHealth",
    "rwpp auto load" to "rwpp.tactical.autoLoad",
    "rwpp building planning" to "rwpp.planning.toggle",
    "rwpp blueprint selection" to "rwpp.planning.selectBlueprint",
)

/** Resolve engine action names through product-owned translations while keeping unknown actions intact. */
internal fun hotkeyActionLabel(
    name: String,
    translate: (key: String, argument: Int?) -> String = { key, argument ->
        if (argument == null) readI18n(key) else readI18n(key, argument)
    },
): String {
    val normalized = name.lowercase(Locale.ROOT)
    hotkeyActionTranslationKeys[normalized]?.let { return translate(it, null) }

    val number = normalized.substringAfterLast(' ').toIntOrNull() ?: return name
    val key = when (normalized.substringBeforeLast(' ')) {
        "unit action" -> "rwpp.hotkeys.action.unitAction"
        "select group" -> "rwpp.hotkeys.action.selectGroup"
        "create group" -> "rwpp.hotkeys.action.createGroup"
        "add group to selection" -> "rwpp.hotkeys.action.addGroupToSelection"
        else -> return name
    }
    return translate(key, number)
}
