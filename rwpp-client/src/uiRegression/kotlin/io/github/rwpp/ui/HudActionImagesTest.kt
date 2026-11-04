package io.github.rwpp.ui

import com.corrodinggames.rts.game.units.actions.GuardAction
import com.corrodinggames.rts.game.units.actions.PatrolAction
import io.github.rwpp.graphics.ActionImages
import io.github.rwpp.graphics.HudIconRequest
import io.github.rwpp.graphics.hudIconRequest
import io.github.rwpp.graphics.hudImageFromPixels
import kotlin.test.*

class HudActionImagesTest {
    private val singleton = com.corrodinggames.rts.gameFramework.GameEngine::class.java
        .getDeclaredField("al").apply { isAccessible = true }
    private var previousEngine: Any? = null

    @kotlin.test.BeforeTest
    fun establishNativeEngineBeforeTeamInitialization() {
        previousEngine = singleton.get(null)
        if (previousEngine == null) {
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                .get(null) as sun.misc.Unsafe
            singleton.set(null, unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java))
        }
    }

    @kotlin.test.AfterTest
    fun restoreNativeEngine() {
        ActionImages.clear(); singleton.set(null, previousEngine)
    }

    @Test
    fun mapPingDismissAndPauseConfirmationHaveBothClientLanguageResources() {
        for (language in listOf("en", "zh")) {
            val path = java.nio.file.Path.of("src/commonMain/composeResources/files/rwpp_${language}.properties")
            val values = java.util.Properties().apply {
                java.nio.file.Files.newBufferedReader(path, Charsets.UTF_8).use(::load)
            }
            for (key in listOf("rwpp.common.cancel", "rwpp.common.confirm")) {
                kotlin.test.assertTrue(!values.getProperty(key).isNullOrBlank(), "$key must exist in $language")
            }
        }
    }

    private fun target(): com.corrodinggames.rts.game.units.Unit {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return (unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Tank::class.java)
                as com.corrodinggames.rts.game.units.Unit).apply { team = com.corrodinggames.rts.game.Team.blankPlayer }
    }

    @Test
    fun equalRebuiltActionsAndDifferentSelectedFactoriesShareTheCompletedNativeImage() {
        val original = GuardAction()
        val rebuilt = GuardAction()
        assertNotSame(original, rebuilt)
        assertEquals(original, rebuilt)
        assertEquals(
            original.hudIconRequest(target()).key, rebuilt.hudIconRequest(target()).key,
            "switching factories or native action instances must not discard identical pixels"
        )
        assertNotEquals(original.hudIconRequest(target()).key, PatrolAction().hudIconRequest(target()).key)
    }

    private fun request(): HudIconRequest {
        val base = GuardAction().hudIconRequest(target())
        val texture = com.corrodinggames.rts.gameFramework.render.BitmapOrTexture().apply {
            width = 2; height = 2; computeHalfSizes()
        }
        return base.copy(icon = texture, key = base.key.copy(iconId = texture.id, sourceWidth = 2, sourceHeight = 2))
    }

    @Test
    fun repeatedFramesAndSelectionChangesNeverPublishPendingOrReplaceCompletedImages() {
        ActionImages.clear()
        val recipe = request()
        val image = hudImageFromPixels(intArrayOf(0xff3080c0.toInt()), 1, 1)
        repeat(40) { assertNull(ActionImages.request(recipe)) }
        var draws = 0
        ActionImages.renderPending {
            draws++
            // A frame arriving while the render is in flight must not enqueue a second draw.
            assertNull(ActionImages.request(recipe.copy()))
            image
        }
        repeat(100) { assertSame(image, ActionImages.request(recipe.copy())) }
        ActionImages.renderPending { error("completed/in-flight images must not be scheduled again") }
        assertEquals(1, draws)
        // Texture reload/crop/team/tech changes are different visual inputs, not a retry timer.
        for (key in listOf(
            recipe.key.copy(iconVersion = recipe.key.iconVersion + 1),
            recipe.key.copy(left = 1), recipe.key.copy(teamColor = 123), recipe.key.copy(techLevel = 2)
        )) {
            assertNull(ActionImages.request(recipe.copy(key = key)))
        }
        ActionImages.clear()
    }

    @Test
    fun intentionallyTransparentNativeUnitsRemainValidCompletedImages() {
        ActionImages.clear()
        val recipe = request()
        val image = hudImageFromPixels(intArrayOf(0), 1, 1)
        ActionImages.request(recipe)
        ActionImages.renderPending { image }
        assertSame(image, ActionImages.request(recipe))
        ActionImages.renderPending { error("native transparency is not a failure or retry condition") }
        ActionImages.clear()
    }

    @Test
    fun failuresAreReportedOnceAndNeverRetriedByTheDisplayFrame() {
        ActionImages.clear()
        val recipe = request()
        ActionImages.request(recipe)
        var draws = 0
        ActionImages.renderPending { draws++; error("deterministic unsupported native texture") }
        repeat(20) {
            assertNull(ActionImages.request(recipe))
            ActionImages.renderPending { draws++; error("unexpected retry") }
        }
        assertEquals(1, draws)
        ActionImages.clear()
    }

    @Test
    fun visibleRequestsPreemptProductionAndRecentlyUsedImagesSurviveCachePressure() {
        ActionImages.clear()
        val recipe = request()
        val image = hudImageFromPixels(intArrayOf(0xff3080c0.toInt()), 1, 1)
        fun numbered(index: Int) = recipe.copy(key = recipe.key.copy(actionId = "action-$index"))
        // Fill the bounded cache, then touch its oldest member and add another entry.
        for (index in 0 until 512) {
            ActionImages.request(numbered(index))
            if (index % 4 == 3) ActionImages.renderPending { image }
        }
        assertSame(image, ActionImages.request(numbered(0)))
        ActionImages.request(numbered(512)); ActionImages.renderPending { image }
        assertSame(image, ActionImages.request(numbered(0)), "Visible use updates recency")
        assertNull(ActionImages.request(numbered(1)), "Least recently used item is evicted")
        ActionImages.clear()

        repeat(20) { ActionImages.request(numbered(it)) }
        val urgent = numbered(19)
        ActionImages.request(urgent, visible = true)
        val order = mutableListOf<String>()
        ActionImages.renderPending { order += it.key.actionId; image }
        assertEquals("action-19", order.first(), "A visible action must not wait behind factory labels")
        assertEquals(4, order.size, "The native-frame render budget remains bounded")
    }

    @Test
    fun reloadDropsOldInFlightResultsWithoutDiscardingNewRequests() {
        ActionImages.clear()
        val recipe = request()
        val oldImage = hudImageFromPixels(intArrayOf(0xff112233.toInt()), 1, 1)
        val newImage = hudImageFromPixels(intArrayOf(0xff445566.toInt()), 1, 1)
        ActionImages.request(recipe)
        var draws = 0
        ActionImages.renderPending {
            draws++
            if (draws == 1) {
                ActionImages.clear()
                assertNull(ActionImages.request(recipe))
                oldImage
            } else newImage
        }
        assertEquals(2, draws)
        assertSame(newImage, ActionImages.request(recipe))
    }

    @Test
    fun rallyPointActionsUseTheirOwnGlyphInsteadOfTheWaypointOrUnitPreview() {
        assertEquals(HudActionGlyph.RALLY, hudActionGlyph("Action - Set Rally"))
        assertEquals(HudActionGlyph.RALLY, hudActionGlyph("设置集结点"))
        assertEquals(HudActionGlyph.WAYPOINT, hudActionGlyph("Patrol / Waypoint"))
        assertEquals(HudActionGlyph.UPGRADE, hudActionGlyph("Upgrade"))
        assertNull(hudActionGlyph("Attack"))
    }
}
