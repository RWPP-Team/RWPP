package io.github.rwpp.api

import kotlin.test.Test
import kotlin.test.assertEquals

class InitializationRegistryTest {
    private class Hook : Initialization {
        var initCount = 0;
        var readyCount = 0
        override fun init() {
            initCount++
        }

        override fun onGameLoaded() {
            readyCount++
        }
    }

    @Test
    fun lateClientModulesCatchUpWithoutReinitializingExistingServices() {
        val registry = InitializationRegistry()
        val api = Hook();
        val client = Hook()
        registry.initialize(listOf(api), false)
        registry.initialize(listOf(api), true)
        registry.initialize(listOf(api, client), true)
        registry.initialize(listOf(api, client), true)
        assertEquals(1, api.initCount); assertEquals(1, api.readyCount)
        assertEquals(1, client.initCount); assertEquals(1, client.readyCount)
    }

    @Test
    fun sameClassInstancesEachReceiveTheirOwnLifecycle() {
        val registry = InitializationRegistry();
        val a = Hook();
        val b = Hook()
        registry.initialize(listOf(a, b), true)
        assertEquals(1, a.initCount); assertEquals(1, b.initCount)
    }
}
