package io.github.rwpp.ui

import android.graphics.Paint
import io.github.rwpp.graphics.NativePaintFlagsAccess
import io.github.rwpp.graphics.enableNativePaintFiltering
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePaintFlagsTest {
    @Test
    fun filteringWorksWhenPaintWasLoadedBeforeItsMixin() {
        val paint = Paint()
        assertFalse(paint is NativePaintFlagsAccess, "This fixture must exercise the pre-mixin fallback")

        val flagsField = Paint::class.java.getDeclaredField("flags").apply { isAccessible = true }
        val originalFlags = flagsField.getInt(paint)
        enableNativePaintFiltering(paint)

        val updatedFlags = flagsField.getInt(paint)
        assertTrue((updatedFlags and 2) != 0, "Bitmap filtering flag should be enabled")
        assertTrue(
            (updatedFlags and originalFlags) == originalFlags,
            "Existing Paint flags should be preserved",
        )
    }
}
