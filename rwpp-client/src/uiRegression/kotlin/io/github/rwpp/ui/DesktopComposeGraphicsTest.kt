package io.github.rwpp.ui

import org.junit.Before

/** Custom test scenes use the same supported backend bootstrap as the desktop host. */
abstract class DesktopComposeGraphicsTest {
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Before
    fun registerGraphicsBackend() {
        androidx.compose.ui.platform.registerSkikoComposeImplementation()
    }
}
