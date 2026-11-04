/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.gameFramework.GameEngine

/** Native skirmish turns networking off after map load but retains its local observer identity. */
internal fun isAeroLiveObserver(engine: GameEngine): Boolean =
    (engine.networkEngine.networked || engine.networkEngine.singlePlayerServer) &&
            engine.networkEngine.localTeam?.isSpectator == true && !engine.replayEngine.isPlayingReplay
