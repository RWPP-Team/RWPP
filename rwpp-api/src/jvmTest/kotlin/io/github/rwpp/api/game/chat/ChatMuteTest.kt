package io.github.rwpp.api.game.chat

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.event.events.ChatMessageEvent
import io.github.rwpp.api.event.events.ChatOrigin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMuteTest {
    @Test
    fun filtersOnlyTheMutedRoomPlayerResolvedFromTheNativeSenderSlot() {
        withNativeGameEngine { unsafe ->
            fun player(id: Int) =
                (unsafe.allocateInstance(Player::class.java) as Player).apply { teamId = id; name = "same" }

            val muted = player(1)
            val other = player(2)
            val replacement = player(1)
            val roster: List<Team> = listOf(muted, other)
            fun event(slot: Int, origin: ChatOrigin = ChatOrigin.RECEIVE) =
                ChatMessageEvent("same", "hello", null, slot, origin)

            ChatMute.clear()
            try {
                val previousRevision = ChatMute.revision.value
                ChatMute.setMuted(muted, true)
                assertTrue(
                    ChatMute.revision.value > previousRevision,
                    "Direct API changes must publish revision updates"
                )
                assertTrue(ChatMute.filters(event(1), roster))
                assertFalse(ChatMute.filters(event(2), roster))
                assertFalse(ChatMute.filters(event(-1), roster), "Ambiguous names do not identify a sender")
                assertFalse(ChatMute.filters(event(1, ChatOrigin.REMOTE_RELAY), roster))
                assertFalse(
                    ChatMute.filters(event(1), listOf(replacement, other)),
                    "A reused slot is not the muted player"
                )
            } finally {
                ChatMute.clear()
            }
        }
    }

    @Test
    fun clearingRoomMuteStateDoesNotCarryIntoTheNextRoom() {
        withNativeGameEngine { unsafe ->
            val player = unsafe.allocateInstance(Player::class.java) as Player
            ChatMute.clear()
            try {
                ChatMute.setMuted(player, true)
                assertTrue(ChatMute.isMuted(player))
                val mutedRevision = ChatMute.revision.value
                ChatMute.clear()
                assertTrue(ChatMute.revision.value > mutedRevision)
                assertFalse(ChatMute.isMuted(player))
            } finally {
                ChatMute.clear()
            }
        }
    }
}

private fun <T> withNativeGameEngine(block: (sun.misc.Unsafe) -> T): T {
    val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        .get(null) as sun.misc.Unsafe
    val engineField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
    val previousEngine = engineField.get(null)
    engineField.set(null, unsafe.allocateInstance(RWGameEngine::class.java))
    try {
        return block(unsafe)
    } finally {
        engineField.set(null, previousEngine)
    }
}
