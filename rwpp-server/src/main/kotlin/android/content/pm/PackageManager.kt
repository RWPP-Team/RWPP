package android.content.pm

/** Exception linkage for the game's mobile-only package query branches. */
class PackageManager private constructor() {
    class NameNotFoundException @JvmOverloads constructor(message: String? = null) : Exception(message)
}
