package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.Packet
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import kotlin.test.*

class ClientSessionSafetyTest {
    private val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        .get(null) as sun.misc.Unsafe

    private fun network() = unsafe.allocateInstance(NetworkEngine::class.java) as NetworkEngine
    private fun connection(socket: Socket) = (unsafe.allocateInstance(Connection::class.java) as Connection).apply {
        this.socket = socket; connected = true
    }

    @Test
    fun replacementIsBoundBeforeWorkersAndRejectsOldLiveSocketsAndRelayChildren() {
        val network = network().apply { networked = true }
        Socket().use { old ->
            Socket().use { current ->
                val oldConnection = connection(old)
                val live = connection(current)
                val oldChild = connection(Socket()).apply { parent = oldConnection }
                val newChild = connection(Socket()).apply { parent = live }
                try {
                    repeat(100) {
                        ClientPacketSessionGuard.begin(network, old)
                        assertFalse(
                            ClientPacketSessionGuard.isStale(
                                network,
                                Packet(35).apply { connection = oldConnection })
                        )
                        ClientPacketSessionGuard.begin(network, current)
                        for (type in listOf(10, 35, 115, 120, 178)) {
                            assertTrue(
                                ClientPacketSessionGuard.isStale(
                                    network,
                                    Packet(type).apply { connection = oldConnection })
                            )
                            assertTrue(
                                ClientPacketSessionGuard.isStale(
                                    network,
                                    Packet(type).apply { connection = oldChild })
                            )
                            assertFalse(
                                ClientPacketSessionGuard.isStale(
                                    network,
                                    Packet(type).apply { connection = live })
                            )
                            assertFalse(
                                ClientPacketSessionGuard.isStale(
                                    network,
                                    Packet(type).apply { connection = newChild })
                            )
                        }
                    }
                    assertFalse(
                        ClientPacketSessionGuard.isStale(network, Packet(35)),
                        "Local packets retain native behavior"
                    )
                    network.networked = false
                    assertTrue(ClientPacketSessionGuard.isStale(network, Packet(35).apply { connection = live }))
                } finally {
                    oldChild.socket.close(); newChild.socket.close()
                }
            }
        }
    }

    @Test
    fun aheadFrameIsPausedWithoutChangingTicksAndOnlyAuthoritativeResyncCanResumeIt() {
        val singleton = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previous = singleton.get(null)
        val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
        val network = network().apply { networked = true; gameStarted = true }
        engine.networkEngine = network
        singleton.set(null, engine)
        try {
            repeat(100) {
                engine.simulationTick = 25745
                network.nextBlockingFrame = 25686
                assertTrue(ClientTimelineGuard.beforeStep(network))
                assertEquals(25745, engine.simulationTick)
                assertEquals(25686, network.nextBlockingFrame)
                assertTrue(ClientTimelineGuard.isPaused(engine))
                network.nextBlockingFrame = 25800
                ClientTimelineGuard.poll(network)
                assertTrue(ClientTimelineGuard.isPaused(engine), "Ordinary step packets cannot repair a future world")
                engine.simulationTick = 25686 // Native server save supplied this state and tick together.
                network.nextBlockingFrame = 25687
                network.resyncCount++
                ClientTimelineGuard.poll(network)
                assertFalse(ClientTimelineGuard.isPaused(engine))
                assertFalse(network.waitingForServerStep)
                assertFalse(ClientTimelineGuard.beforeStep(network))
            }
            network.isServer = true
            engine.simulationTick = 10; network.nextBlockingFrame = 0
            assertFalse(ClientTimelineGuard.beforeStep(network), "The client gate must not alter host simulation")
            network.isServer = false
            assertTrue(ClientTimelineGuard.beforeStep(network))
            val replacement = this.network().apply { networked = true; nextBlockingFrame = 11 }
            engine.networkEngine = replacement
            ClientTimelineGuard.poll(replacement)
            assertFalse(ClientTimelineGuard.isPaused(engine), "An old network must not freeze its replacement")
            assertFalse(ClientTimelineGuard.beforeStep(replacement))
        } finally {
            engine.networkEngine = network
            network.networked = false; ClientTimelineGuard.poll(network)
            singleton.set(null, previous)
        }
    }

    @Test
    fun reflectiveFailuresKeepTheirCauseAndVmFailuresStillPropagate() {
        val error = IllegalStateException("native frame failure")
        assertSame(error, nativeGameFailure(InvocationTargetException(InvocationTargetException(error))))
        assertFailsWith<OutOfMemoryError> { nativeGameFailure(InvocationTargetException(OutOfMemoryError("test"))) }
        assertFailsWith<ThreadDeath> { nativeGameFailure(ThreadDeath()) }
    }
}
