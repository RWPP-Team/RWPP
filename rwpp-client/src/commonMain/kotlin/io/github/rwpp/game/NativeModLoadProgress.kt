package io.github.rwpp.game

import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.widget.loadingMessage
import io.github.rwpp.widget.loadingOverlayKeepsContent

internal class ModMemoryLoadFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal class NativeModLoadProgress {
    val failedMods = linkedSetOf<String>()
    private var lastProgressNanos = 0L

    fun run(load: () -> Unit) {
        current.set(this)
        try {
            load()
        } finally {
            current.remove()
        }
    }

    companion object {
        private val current = ThreadLocal<NativeModLoadProgress>()

        fun report(message: String?) {
            val operation = current.get() ?: return
            if (!loadingOverlayKeepsContent || message == null) return
            val now = System.nanoTime()
            if (now - operation.lastProgressNanos < 125_000_000L) return
            val unit = nativeUnitProgress.matchEntire(message)
            loadingMessage = if (unit != null) readI18n("rwpp.mod.loadingUnits", unit.groupValues[1], unit.groupValues[2])
            else message
            operation.lastProgressNanos = now
            presentNativeModProgress()
        }

        /** Returns false during engine startup, when there is no in-app reload transaction. */
        fun failed(title: String): Boolean = current.get()?.let {
            it.failedMods.add(title)
            true
        } ?: false

        private val nativeUnitProgress = Regex("""Loading units - (\d+) \((.*)\)""")
    }
}
