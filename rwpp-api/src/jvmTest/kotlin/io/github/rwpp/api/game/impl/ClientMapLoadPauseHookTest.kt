package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.game.NetworkGameLoadingState
import io.github.rwpp.api.mixin.network.NativeNetworkLifecycleMixin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientMapLoadPauseHookTest {
    @Test
    fun returnValueHookPausesOnlyNetworkClientsDuringPacket120MapLoadAndPreservesNativePause() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val singletonField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previousEngine = singletonField.get(null)
        val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
        val network = unsafe.allocateInstance(NetworkEngine::class.java) as NetworkEngine
        engine.networkEngine = network
        network.networked = true
        network.isServer = false

        try {
            singletonField.set(null, engine)
            NetworkGameLoadingState.finishMapLoad()
            assertFalse(NativeNetworkLifecycleMixin.awaitClientMapLoad(false))

            NetworkGameLoadingState.beginMapLoad()
            assertTrue(NativeNetworkLifecycleMixin.awaitClientMapLoad(false))

            network.isServer = true
            assertFalse(NativeNetworkLifecycleMixin.awaitClientMapLoad(false))
            assertTrue(NativeNetworkLifecycleMixin.awaitClientMapLoad(true))

            network.isServer = false
            network.networked = false
            assertFalse(NativeNetworkLifecycleMixin.awaitClientMapLoad(false))
            assertTrue(NativeNetworkLifecycleMixin.awaitClientMapLoad(true))

            network.networked = true
            NetworkGameLoadingState.finishMapLoad()
            assertFalse(NativeNetworkLifecycleMixin.awaitClientMapLoad(false))
        } finally {
            NetworkGameLoadingState.finishMapLoad()
            singletonField.set(null, previousEngine)
        }
    }
}
