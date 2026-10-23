package android.graphics

/** Enum values carried by the game's existing desktop color-filter implementations. */
object PorterDuff {
    enum class Mode {
        CLEAR,
        SRC,
        DST,
        SRC_OVER,
        DST_OVER,
        SRC_IN,
        DST_IN,
        SRC_OUT,
        DST_OUT,
        SRC_ATOP,
        DST_ATOP,
        XOR,
        DARKEN,
        LIGHTEN,
        MULTIPLY,
        SCREEN,
        ADD,
        OVERLAY,
    }
}
