package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.units.Unit as NativeUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.actions.SpecialQueueAction
import com.corrodinggames.rts.game.units.buildings.Factory
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.game.units.buildings.LandFactory
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.GameVisualSettings
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverflowProductionQueueTest {
    @Test
    fun factoriesDoNotAllSpendTheSameAffordableBalance() {
        val singleton = field(GameEngine::class.java, "al")
        val cachedSettings = field(GameVisualSettings::class.java, "cached")
        val previousEngine = singleton.get(null)
        val previousSettings = cachedSettings.get(GameVisualSettings)
        val engine = allocate(RWGameEngine::class.java)
        val queue = OverflowProductionQueue
        val update = queue.javaClass.getDeclaredMethod("update", GameEngine::class.java).apply { isAccessible = true }
        try {
            singleton.set(null, engine)
            cachedSettings.set(GameVisualSettings, Settings().apply { overflowProductionQueue = true })
            field(GameEngine::class.java, "gameUpdateLock").set(engine, Any())
            engine.map = allocate(NativeMap::class.java).apply { mapLoaded = true }
            engine.networkEngine = allocate(NetworkEngine::class.java)
            engine.replayEngine = allocate(ReplayEngine::class.java)
            engine.commandController = CommandController()
            engine.interfaceEngine = allocate(InterfaceEngine::class.java)
            engine.settings = allocate(SettingsEngine::class.java)
            val team = Player(0, false).apply { credits = 0.0; maxUnitCapCount = 100 }
            engine.playerTeam = team
            val action = TestQueueAction()
            val factories = List(3) { index ->
                allocate(TestFactory::class.java).apply {
                    id = index + 1L; this.team = team; buildProgress = 1f; this.action = action
                    field(Factory::class.java, "buildQueue").set(this, FactoryBuildQueue(this))
                }
            }
            for (factory in factories) assertTrue(queue.enqueue(engine, action, listOf(factory)))
            fun waiting() = factories.map(queue::pendingCountForFactory)
            fun producing() = factories.map { it.getQueuedCountOf(action.storedActionId, false) }
            fun pump() {
                field(queue.javaClass, "nextUpdateAt").setLong(queue, 0)
                update.invoke(queue, engine)
            }
            fun applyNext() {
                val commands = engine.commandController.localPendingCommands
                val command = commands.single() as com.corrodinggames.rts.gameFramework.CommandControllerCommand
                queue.beforeNativeApplication(command)
                command.issueCommand()
                queue.afterNativeApplication(command)
                commands.remove(command)
            }
            assertEquals(listOf(1, 1, 1), waiting())

            team.credits = action.priceValue.toDouble()
            pump()
            assertEquals(1, engine.commandController.localPendingCommands.size,
                "Factories sharing one balance should not all submit a command before the first is charged")
            assertEquals(listOf(1, 1, 1), waiting())
            pump()
            assertEquals(1, engine.commandController.localPendingCommands.size)

            // Another ordinary order can consume the balance before the accepted command's tick.
            team.credits = 0.0
            applyNext()
            assertEquals(listOf(0, 0, 0), producing())
            assertEquals(listOf(1, 1, 1), waiting())

            for (accepted in 1..3) {
                team.credits = action.priceValue.toDouble()
                pump()
                applyNext()
                assertEquals(List(3) { if (it < accepted) 1 else 0 }, producing())
                assertEquals(List(3) { if (it < accepted) 0 else 1 }, waiting())
                pump()
                assertTrue(engine.commandController.localPendingCommands.isEmpty())
            }
        } finally {
            queue.javaClass.getDeclaredMethod("clear").apply { isAccessible = true }.invoke(queue)
            cachedSettings.set(GameVisualSettings, previousSettings)
            singleton.set(null, previousEngine)
        }
    }

    private class TestFactory : LandFactory(true) {
        lateinit var action: TestQueueAction
        override fun getSpecialActions() = arrayListOf<SpecialAction>(action)
        override fun getUnitTypeInterface(): UnitTypeInterface = UnitType.landFactory
    }

    private class TestQueueAction : SpecialQueueAction(UnitType.tank) {
        override fun compareTo(other: Any?): Int = 0
        override fun canAffordForUnit(unit: NativeUnit, includePending: Boolean): Boolean =
            // Reproduce the installed price-probe mixin without starting the extension loader.
            ProductionPriceProbe.allows(customPrice, unit) ?: super.canAffordForUnit(unit, includePending)
    }

    private companion object {
        val unsafe = field(Unsafe::class.java, "theUnsafe").get(null) as Unsafe
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        fun <T> allocate(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
    }
}
