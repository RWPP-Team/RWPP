/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package android.os

/** Binary contract used by the game's geometry classes, independent of the Android SDK. */
interface Parcelable {
    fun describeContents(): Int
    fun writeToParcel(destination: Parcel, flags: Int)

    interface Creator<T> {
        fun createFromParcel(source: Parcel): T
        fun newArray(size: Int): Array<T?>
    }

    interface ClassLoaderCreator<T> : Creator<T> {
        fun createFromParcel(source: Parcel, loader: ClassLoader?): T
    }

    companion object {
        const val CONTENTS_FILE_DESCRIPTOR = 1
        const val PARCELABLE_WRITE_RETURN_VALUE = 1
    }
}
