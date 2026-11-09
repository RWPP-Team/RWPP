/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.room

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.steam.SteamEngineDisabled

/** The native multiplayer host creates and owns the Steam lobby. */
internal fun openRoomSteamInvite(): Boolean {
    if (!GameEngine.isDesktopVersion()) return false
    val network = GameEngine.getInstance()?.networkEngine ?: return false
    if (!network.networked || !network.isServer || network.singlePlayerServer || network.gameStarted) return false

    val steam = SteamEngineDisabled.getInstance() ?: return false
    if (steam.PENDING_returnsFalse()) return false
    steam.showInviteDialog()
    return true
}
