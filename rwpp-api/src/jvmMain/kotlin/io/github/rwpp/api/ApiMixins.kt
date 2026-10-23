/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api

import io.github.rwpp.api.coroutines.GameDispatcherMixin
import io.github.rwpp.api.event.hooks.ClientEventMixin
import io.github.rwpp.api.event.hooks.CommandEventMixin
import io.github.rwpp.api.event.hooks.DrawEventMixin
import io.github.rwpp.api.event.hooks.EngineLoadedMixin
import io.github.rwpp.api.event.hooks.FactoryUnitCreationEventMixin
import io.github.rwpp.api.event.hooks.FileSaveCommitMixin
import io.github.rwpp.api.event.hooks.MapTriggerEventMixin
import io.github.rwpp.api.event.hooks.ModReloadEventHooks
import io.github.rwpp.api.event.hooks.NativeDecisionMixin
import io.github.rwpp.api.event.hooks.NativeEngineLifecycleMixin
import io.github.rwpp.api.event.hooks.NativeJoinDecisionMixin
import io.github.rwpp.api.event.hooks.NativeNetworkLifecycleMixin
import io.github.rwpp.api.event.hooks.NativeReplayLifecycleMixin
import io.github.rwpp.api.event.hooks.NetworkEventMixin
import io.github.rwpp.api.event.hooks.PersistenceEventMixin
import io.github.rwpp.api.event.hooks.PlayerEventMixin
import io.github.rwpp.api.event.hooks.ReplaySnapshotSaveMixin
import io.github.rwpp.api.event.hooks.RefreshUiEventMixin
import io.github.rwpp.api.event.hooks.SimulationEventHooks
import io.github.rwpp.api.event.hooks.UnitDrawEntryMixin
import io.github.rwpp.api.event.hooks.UnitEventMixin
import io.github.rwpp.api.event.hooks.UnitMetadataEventMixin
import io.github.rwpp.api.event.hooks.UnitOverlayBarMixin
import io.github.rwpp.api.event.hooks.UnitSpawnRegistryMixin
import io.github.rwpp.api.event.hooks.UnitUpgradeEventMixin
import io.github.rwpp.api.game.ai.AiControllerHooks
import io.github.rwpp.api.game.ai.AiTeamLifecycleHooks
import io.github.rwpp.api.net.impl.NetworkPacketMixin
import io.github.rwpp.api.net.impl.PayloadCapabilityMixin

/**
 * API 自带的 Mixin 清单，供把 API 作为内置扩展的宿主注册。
 *
 * API 通常由使用它的扩展归档提供，此时宿主扫描归档即可发现这些类。以库的形式放进宿主类路径时没有归档可供扫描，
 * 而内置扩展又必须由宿主负责注册自己的 Mixin，因此这里给出完整清单。
 *
 * 清单必须与 `@AsmMixin` 标注的类保持一致。新增或删除 Mixin 时同步本文件，遗漏会让对应的事件或命令在服务端
 * 静默失效。
 */
object ApiMixins {

    /** 全部 Mixin 类，顺序无关。 */
    @JvmStatic
    fun classes(): List<Class<*>> = listOf(
        io.github.rwpp.api.net.impl.NativeTcpConnectionMixin::class.java,
        io.github.rwpp.api.net.impl.NativeTcpConnectMixin::class.java,
        io.github.rwpp.api.net.impl.NativeAddressConnectMixin::class.java,
        io.github.rwpp.api.net.impl.NativeTcpListenMixin::class.java,
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
        SimulationEventHooks::class.java,
        UnitDrawEntryMixin::class.java,
        UnitOverlayBarMixin::class.java,
        CommandEventMixin::class.java,
        UnitEventMixin::class.java,
        UnitUpgradeEventMixin::class.java,
        FactoryUnitCreationEventMixin::class.java,
        UnitSpawnRegistryMixin::class.java,
        AiControllerHooks::class.java,
        AiTeamLifecycleHooks::class.java,
        NetworkPacketMixin::class.java,
        PayloadCapabilityMixin::class.java,
    )
}
