package io.github.rwpp.ui

/** Native drawing owns HUD cadence; display callbacks only cover a stopped/absent game surface. */
internal class NativeHudFrameSchedule {
    @Volatile private var lastNativeFrame: Long? = null

    fun onNativeFrame(now: Long) {
        lastNativeFrame = now
    }

    fun needsDisplayFallback(now: Long): Boolean {
        val previous = lastNativeFrame ?: return true
        return now - previous >= 100_000_000L
    }
}
