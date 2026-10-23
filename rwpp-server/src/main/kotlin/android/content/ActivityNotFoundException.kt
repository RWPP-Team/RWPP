package android.content

/** Exception type referenced by shared platform helpers, even in dedicated mode. */
class ActivityNotFoundException @JvmOverloads constructor(message: String? = null) : RuntimeException(message)
