package android.os

/** Desktop identity for the shared game engine; no Android platform is present. */
object Build {
    const val MODEL = "RWPP Server"
    const val MANUFACTURER = "RWPP"

    object VERSION {
        const val RELEASE = "desktop"
        const val SDK_INT = 0
        const val SDK = "0"
    }
}
