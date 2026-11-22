/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.api.extension

import io.github.rwml.api.ExtensionContext
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A setting the client can render without depending on the extension's UI toolkit. */
sealed class ExtensionSetting<T>(val label: String, val read: () -> T, val write: (T) -> Unit) {
    init {
        require(label.isNotBlank()) { "An extension setting needs a label" }
    }

    class Toggle(label: String, read: () -> Boolean, write: (Boolean) -> Unit) :
        ExtensionSetting<Boolean>(label, read, write)

    /** The value is a zero-based index into [options]. */
    class Choice(label: String, options: List<String>, read: () -> Int, write: (Int) -> Unit) :
        ExtensionSetting<Int>(label, read, write) {
        val options: List<String> = Collections.unmodifiableList(ArrayList(options))

        init {
            require(this.options.isNotEmpty() && this.options.none(String::isBlank)) {
                "A choice setting needs non-empty options"
            }
        }
    }

    class Text(label: String, read: () -> String, write: (String) -> Unit) :
        ExtensionSetting<String>(label, read, write)
}

/** Contributions displayed for loaded extensions in the RWPP client. */
object ExtensionSettings {
    private val registrations = MutableStateFlow<Map<String, List<ExtensionSetting<*>>>>(emptyMap())
    val panels: StateFlow<Map<String, List<ExtensionSetting<*>>>> = registrations.asStateFlow()

    /**
     * Registers one settings panel for [owner]. Call from the extension's onLoad.
     *
     * RWML removes the panel when the extension unloads or fails to load. The client calls
     * the callbacks on its UI thread. Keep them short, and own validation and persistence
     * in the extension. Closing the returned handle early unregisters the panel.
     */
    fun register(owner: ExtensionContext, settings: List<ExtensionSetting<*>>): AutoCloseable {
        require(settings.isNotEmpty()) { "An extension settings panel needs at least one setting" }
        val id = owner.extensionId()
        val panel = Collections.unmodifiableList(ArrayList(settings))
        val registration = AutoCloseable {
            registrations.update { active ->
                if (active[id] === panel) Collections.unmodifiableMap(active - id) else active
            }
        }
        registrations.update { active ->
            check(id !in active) { "Settings already registered for extension $id" }
            Collections.unmodifiableMap(active + (id to panel))
        }
        return try {
            owner.own(registration)
        } catch (failure: Throwable) {
            registration.close()
            throw failure
        }
    }
}
