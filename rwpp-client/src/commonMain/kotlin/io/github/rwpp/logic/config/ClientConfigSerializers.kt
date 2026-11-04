/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.config

import io.github.rwpp.api.config.ConfigSerializers
import io.github.rwpp.config.*

/** Register generated serializers directly, before any configuration is restored. */
internal fun registerClientConfigSerializers() {
    ConfigSerializers.register(Settings::class.java, Settings.serializer())
    ConfigSerializers.register(CoreData::class.java, CoreData.serializer())
    ConfigSerializers.register(Blacklists::class.java, Blacklists.serializer())
    ConfigSerializers.register(EnabledExtensions::class.java, EnabledExtensions.serializer())
    ConfigSerializers.register(HudComponentDataList::class.java, HudComponentDataList.serializer())
    ConfigSerializers.register(MultiplayerPreferences::class.java, MultiplayerPreferences.serializer())
    ConfigSerializers.register(MatchHistory::class.java, MatchHistory.serializer())
}
