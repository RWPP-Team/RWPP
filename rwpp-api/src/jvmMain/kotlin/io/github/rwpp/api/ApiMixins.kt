/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api

import io.github.rwpp.api.mixin.coroutines.GameDispatcherMixin
import io.github.rwpp.api.mixin.event.ClientEventMixin
import io.github.rwpp.api.mixin.event.CommandEventMixin
import io.github.rwpp.api.mixin.event.DrawEventMixin
import io.github.rwpp.api.mixin.event.EngineLoadedMixin
import io.github.rwpp.api.mixin.event.FactoryUnitCreationEventMixin
import io.github.rwpp.api.mixin.event.FileSaveCommitMixin
import io.github.rwpp.api.mixin.event.MapTriggerEventMixin
import io.github.rwpp.api.mixin.event.ModReloadEventHooks
import io.github.rwpp.api.mixin.event.NetworkEventMixin
import io.github.rwpp.api.mixin.event.PersistenceEventMixin
import io.github.rwpp.api.mixin.event.PlayerEventMixin
import io.github.rwpp.api.mixin.event.RefreshUiEventMixin
import io.github.rwpp.api.mixin.event.ReplaySnapshotSaveMixin
import io.github.rwpp.api.mixin.event.SimulationEventMixin
import io.github.rwpp.api.mixin.event.UnitDrawEntryMixin
import io.github.rwpp.api.mixin.event.UnitEventMixin
import io.github.rwpp.api.mixin.event.UnitMetadataEventMixin
import io.github.rwpp.api.mixin.event.UnitOverlayBarMixin
import io.github.rwpp.api.mixin.event.UnitSpawnRegistryMixin
import io.github.rwpp.api.mixin.event.UnitUpgradeEventMixin
import io.github.rwpp.api.mixin.game.NativeEngineLifecycleMixin
import io.github.rwpp.api.mixin.game.NativeReplayLifecycleMixin
import io.github.rwpp.api.mixin.game.ai.AiControllerHooks
import io.github.rwpp.api.mixin.game.ai.AiTeamLifecycleHooks
import io.github.rwpp.api.mixin.game.ai.AiUnitMixActionSnapshotMixin
import io.github.rwpp.api.mixin.game.stats.NativeStatsHistoryAccessMixin
import io.github.rwpp.api.mixin.game.stats.StatsHistoryLoadMixin
import io.github.rwpp.api.mixin.network.NativeAddressConnectMixin
import io.github.rwpp.api.mixin.network.NativeDecisionMixin
import io.github.rwpp.api.mixin.network.NativeJoinDecisionMixin
import io.github.rwpp.api.mixin.network.NativeNetworkLifecycleMixin
import io.github.rwpp.api.mixin.network.NativeTcpConnectMixin
import io.github.rwpp.api.mixin.network.NativeTcpConnectionMixin
import io.github.rwpp.api.mixin.network.NativeTcpListenMixin
import io.github.rwpp.api.mixin.network.NetworkPacketMixin
import io.github.rwpp.api.mixin.network.PayloadCapabilityMixin

/**
 * API-owned mixin inventory used when the API is registered as a built-in host extension.
 * Keep this list synchronized with all `@AsmMixin` classes under `io.github.rwpp.api.mixin`.
 */
object ApiMixins {
    /** All API mixin classes; ordering is not significant. */
    @JvmStatic
    fun classes(): List<Class<*>> = listOf(
        NativeTcpConnectionMixin::class.java,
        NativeTcpConnectMixin::class.java,
        NativeAddressConnectMixin::class.java,
        NativeTcpListenMixin::class.java,
        GameDispatcherMixin::class.java,
        EngineLoadedMixin::class.java,
        DrawEventMixin::class.java,
        PlayerEventMixin::class.java,
        ClientEventMixin::class.java,
        UnitMetadataEventMixin::class.java,
        MapTriggerEventMixin::class.java,
        NetworkEventMixin::class.java,
        RefreshUiEventMixin::class.java,
        ModReloadEventHooks::class.java,
        NativeJoinDecisionMixin::class.java,
        NativeDecisionMixin::class.java,
        NativeNetworkLifecycleMixin::class.java,
        NativeEngineLifecycleMixin::class.java,
        NativeReplayLifecycleMixin::class.java,
        PersistenceEventMixin::class.java,
        FileSaveCommitMixin::class.java,
        ReplaySnapshotSaveMixin::class.java,
        SimulationEventMixin::class.java,
        UnitDrawEntryMixin::class.java,
        UnitOverlayBarMixin::class.java,
        CommandEventMixin::class.java,
        UnitEventMixin::class.java,
        UnitUpgradeEventMixin::class.java,
        FactoryUnitCreationEventMixin::class.java,
        UnitSpawnRegistryMixin::class.java,
        AiControllerHooks::class.java,
        AiTeamLifecycleHooks::class.java,
        AiUnitMixActionSnapshotMixin::class.java,
        NetworkPacketMixin::class.java,
        PayloadCapabilityMixin::class.java,
        StatsHistoryLoadMixin::class.java,
        NativeStatsHistoryAccessMixin::class.java,
    )
}
