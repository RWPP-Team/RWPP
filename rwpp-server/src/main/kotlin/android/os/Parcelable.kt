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
