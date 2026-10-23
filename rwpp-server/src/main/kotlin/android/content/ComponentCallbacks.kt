package android.content

import android.content.res.Configuration

interface ComponentCallbacks {
    fun onConfigurationChanged(configuration: Configuration)
    fun onLowMemory()
}

interface ComponentCallbacks2 : ComponentCallbacks {
    fun onTrimMemory(level: Int)
}
