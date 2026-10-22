/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net

enum class InternalPacketType(@JvmField val type: Int) {
    /**
     * CUSTOM PACKET
     *
     * Legacy extension packet types. Typed PacketKey IDs use 500-999; packet 1000 is the
     * API-owned namespaced payload envelope.
     */
    SERVER_DEBUG_RECEIVE(2000),
    SERVER_DEBUG(2001),

    PRE_GET_SERVER_INFO_FROM_LIST(600),
    RECEIVE_SERVER_INFO_FROM_LIST(601),

    /* Ex */
    GET_SERVER_INFO_RECEIVE(3000),
    GET_SERVER_INFO(3001),
    UPDATA_CLASS_RECEIVE(3010),
    STATUS_RESULT(3999),

    /**
     * Game Core Packet
     */
    /* Preregister */
    PREREGISTER_INFO_RECEIVE(160),
    PREREGISTER_INFO(161),
    PASSWD_ERROR(113),
    REGISTER_PLAYER(110),

    /* Server Info */
    SERVER_INFO(106),
    TEAM_LIST(115),

    /* Heart */
    HEART_BEAT(108),
    HEART_BEAT_RESPONSE(109),

    /* Chat */
    CHAT_RECEIVE(140),
    CHAT(141),

    /* Net Status */
    PACKET_DOWNLOAD_PENDING(4),
    KICK(150),
    DISCONNECT(111),

    /* StartGame */
    START_GAME(120),
    ACCEPT_START_GAME(112),
    RETURN_TO_BATTLEROOM(122),

    /* GameStart Commands */
    TICK(10),
    GAMECOMMAND_RECEIVE(20),
    SYNCCHECKSUM_STATUS(31),
    SYNC_CHECK(30),
    SYNC(35),

    /* Relay */
    RELAY_117(117),
    RELAY_118_117_RETURN(118),
    RELAY_POW(151),
    RELAY_POW_RECEIVE(152),

    RELAY_VERSION_INFO(163),
    RELAY_BECOME_SERVER(170),
    FORWARD_CLIENT_ADD(172),
    FORWARD_CLIENT_REMOVE(173),
    PACKET_FORWARD_CLIENT_FROM(174),
    PACKET_FORWARD_CLIENT_TO(175),
    PACKET_FORWARD_CLIENT_TO_REPEATED(176),
    PACKET_RECONNECT_TO(178),

    EMPTYP_ACKAGE(0),
    NOT_RESOLVED(-1),
    ;

    companion object {
        fun from(type: Int) = entries.firstOrNull { it.type == type }

        /** Native game protocol IDs; legacy RWPP query/debug IDs are extension protocols. */
        fun isNative(type: Int): Boolean = type in 0..180 && from(type) != null
    }
}
