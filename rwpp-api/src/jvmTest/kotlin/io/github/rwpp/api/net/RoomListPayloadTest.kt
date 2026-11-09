package io.github.rwpp.api.net

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoomListPayloadTest {
    private fun room(uuid: String, name: String): String =
        "$uuid,$name,135,url:https://corrodinggames.com/discord,9DDA0D63DAD2E7,5123,true,," +
                "false,Discord,LINK,LINK,ANY,false,,0,10,true,$uuid,true,,0"

    @Test
    fun readsEveryCompleteRoomRow() {
        val payload = "CORRODINGGAMES[1.0]\n" +
                room("u1", "Alpha") + "\n" +
                room("u2", "Beta") + "\n"

        val rooms = parseRoomListPayload(payload)

        assertEquals(2, rooms.size)
        assertEquals(listOf("Alpha", "Beta"), rooms.map { it.roomOwner })
        assertEquals(135, rooms.first().gameVersion)
    }

    @Test
    fun skipsARecordSplitByANewlineButKeepsTheFollowingRooms() {
        // The official list contains names with an embedded newline, which splits one record over
        // two lines. That record is dropped; the rooms after it must still be listed.
        val payload = "CORRODINGGAMES[1.0]\n" +
                "u1,Find players on the official discord\n Note: opens in browser,135," +
                "url:https://corrodinggames.com/discord,9DDA0D63DAD2E7,5123,true,,false,Discord," +
                "LINK,LINK,ANY,false,,0,10,true,u1,true,,0\n" +
                room("u2", "Beta") + "\n"

        val rooms = parseRoomListPayload(payload)

        assertEquals(1, rooms.size)
        assertEquals("Beta", rooms.single().roomOwner)
    }

    @Test
    fun skipsBrokenRowsWithoutDroppingTheRest() {
        val payload = "CORRODINGGAMES[1.0]\n" +
                "u1,broken\n" +
                room("u2", "Beta") + "\n"

        val rooms = parseRoomListPayload(payload)

        assertEquals(1, rooms.size)
        assertEquals("Beta", rooms.single().roomOwner)
    }

    @Test
    fun rejectsAForeignPayload() {
        assertFailsWith<IOException> {
            parseRoomListPayload("<html>captive portal</html>\n")
        }
    }
}
