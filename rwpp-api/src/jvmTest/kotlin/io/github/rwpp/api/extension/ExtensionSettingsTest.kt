package io.github.rwpp.api.extension

import io.github.rwml.api.ExtensionContext
import io.github.rwml.api.MixinBridge
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionSettingsTest {
    @Test
    fun closingAnOwnerRemovesItsPanelAndStaleHandlesCannotRemoveAReplacement() {
        val owner = Owner("test.settings")
        val setting = ExtensionSetting.Toggle("Enabled", { true }, {})
        val handle = ExtensionSettings.register(owner, listOf(setting))
        try {
            assertEquals(listOf(setting), ExtensionSettings.panels.value[owner.extensionId()])
            assertFailsWith<IllegalStateException> { ExtensionSettings.register(owner, listOf(setting)) }
        } finally {
            owner.close()
        }
        assertFalse(owner.extensionId() in ExtensionSettings.panels.value)

        val replacement = Owner(owner.extensionId())
        ExtensionSettings.register(replacement, listOf(setting)).use {
            handle.close()
            assertEquals(listOf(setting), ExtensionSettings.panels.value[owner.extensionId()])
        }
    }

    @Test
    fun ownershipFailureDoesNotLeaveAVisiblePanel() {
        val owner = Owner("test.failing")
        owner.close()
        assertFailsWith<IllegalStateException> {
            ExtensionSettings.register(owner, listOf(ExtensionSetting.Text("Name", { "initial" }, {})))
        }
        assertFalse(owner.extensionId() in ExtensionSettings.panels.value)
    }

    private class Owner(private val id: String) : ExtensionContext {
        private var closed = false
        private val registrations = ArrayList<AutoCloseable>()
        override fun extensionId(): String = id
        override fun dataDirectory(): Path = error("Unused")
        override fun classLoader(): ClassLoader = javaClass.classLoader
        override fun mixin(): MixinBridge = error("Unused")
        override fun log(message: String) = Unit
        override fun log(message: String, error: Throwable) = Unit

        override fun <T : AutoCloseable> own(resource: T): T {
            check(!closed)
            registrations.add(resource)
            return resource
        }

        fun close() {
            closed = true
            registrations.asReversed().forEach(AutoCloseable::close)
            registrations.clear()
        }
    }
}
