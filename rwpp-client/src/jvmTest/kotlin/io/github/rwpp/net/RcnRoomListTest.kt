package io.github.rwpp.net

import kotlin.test.Test
import kotlin.test.assertEquals

class RcnRoomListTest {
    @Test
    fun listsBothRelayPrefixesWithTheirOriginalJoinCodes() {
        val rooms = RcnRoomList.parse(
            """
            {
              "status": "success",
              "rooms": [
                {"id": "R12345", "nameText": "RCN room", "minPlayer": 3, "maxPlayer": 10},
                {"id": "S67890", "nameText": "SCN room", "minPlayer": 0, "maxPlayer": 8},
                {"id": "S67890", "nameText": "Duplicate"},
                {"id": "Sinvalid", "nameText": "Invalid code"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("R12345", "S67890"), rooms.map { it.addressProvider() })
        assertEquals(listOf("RCN room", "SCN room"), rooms.map { it.creator })
        assertEquals(listOf(3 to 10, 0 to 8), rooms.map { it.playerCurrentCount to it.playerMaxCount })
    }
}
