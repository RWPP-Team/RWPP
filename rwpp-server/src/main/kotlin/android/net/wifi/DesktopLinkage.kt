/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package android.net.wifi

import android.os.Parcel
import android.os.Parcelable

/**
 * Minimal desktop linkage for the native engine's BlueStacks probe. The dedicated server exits that
 * probe before requesting Wi-Fi services; these source-used signatures also let ASM compute frames
 * for the shared GameEngine class without packaging the Android SDK.
 */
open class WifiManager {
    open fun getConnectionInfo(): WifiInfo? = null
}

/** Type linkage for the one Wi-Fi information getter referenced by GameEngine. */
open class WifiInfo : Parcelable {
    open fun getSSID(): String? = null

    override fun describeContents(): Int = 0

    override fun writeToParcel(destination: Parcel, flags: Int) = Unit
}
