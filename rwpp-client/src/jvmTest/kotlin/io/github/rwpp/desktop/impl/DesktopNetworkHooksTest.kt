/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.api.game.RoomOption
import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.mixin.desktop.DesktopNetworkPacketMixin
import io.github.rwpp.mixin.desktop.DesktopRoomOptionHandshakeMixin
import io.github.rwpp.net.*
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.*

class DesktopNetworkHooksTest {
    @Test
    fun `only non-host preregistration response applies a room option`() {
        val originalOption = RoomOption(canTransferMod = false, allModsSize = 1)
        var appliedOption: RoomOption? = null

        assertEquals(
            PreregisterPacketResult.NOT_PREREGISTER,
            applyPreregisterRoomOption(
                packetType = InternalPacketType.PREREGISTER_INFO_RECEIVE.type,
                isHost = true,
                option = originalOption,
                expectedProtocolVersion = originalOption.protocolVersion,
            ) { appliedOption = it },
        )
        assertNull(appliedOption, "the client request must never overwrite a host room option")

        assertEquals(
            PreregisterPacketResult.NATIVE_HANDLED,
            applyPreregisterRoomOption(
                packetType = InternalPacketType.PREREGISTER_INFO.type,
                isHost = false,
                option = originalOption,
                expectedProtocolVersion = originalOption.protocolVersion,
            ) { appliedOption = it },
        )
        assertSame(originalOption, appliedOption)

        appliedOption = null
        assertEquals(
            PreregisterPacketResult.NATIVE_HANDLED,
            applyPreregisterRoomOption(
                packetType = InternalPacketType.PREREGISTER_INFO.type,
                isHost = true,
                option = originalOption,
                expectedProtocolVersion = originalOption.protocolVersion,
            ) { appliedOption = it },
        )
        assertNull(appliedOption, "a server must ignore its own preregistration response")
    }

    @Test
    fun `protocol mismatch requests native packet cancellation after recording the option`() {
        val remoteOption = RoomOption(canTransferMod = true, allModsSize = 4, protocolVersion = -1)
        var appliedOption: RoomOption? = null

        val result =
            applyPreregisterRoomOption(
                packetType = InternalPacketType.PREREGISTER_INFO.type,
                isHost = false,
                option = remoteOption,
                expectedProtocolVersion = remoteOption.protocolVersion + 1,
            ) { appliedOption = it }

        assertEquals(PreregisterPacketResult.CANCEL_FOR_PROTOCOL_MISMATCH, result)
        assertSame(remoteOption, appliedOption)
    }

    @Test
    fun `banned unit and build commands are blocked while allowed commands pass`() {
        val bannedUnits = setOf("heavy_tank", "factory")

        assertTrue(isBannedUnitCommand("u_heavy_tank", false, null, bannedUnits))
        assertTrue(isBannedUnitCommand("build", true, "factory", bannedUnits))
        assertFalse(isBannedUnitCommand("build", true, "scout", bannedUnits))
        assertFalse(isBannedUnitCommand("move", false, "factory", bannedUnits))
        assertTrue(shouldRouteRemoteCommand(true, false, ".rwpp help", ".rwpp"))
        assertFalse(shouldRouteRemoteCommand(true, true, ".rwpp help", ".rwpp"))
        assertFalse(shouldRouteRemoteCommand(false, false, ".rwpp help", ".rwpp"))
        assertTrue(shouldRouteLocalCommand(true, ".rwpp help", ".rwpp"))
        assertFalse(shouldRouteLocalCommand(false, ".rwpp help", ".rwpp"))
    }

    @Test
    fun `player defeat notification is claimed once only for an active defeated player`() {
        val claimedPlayers = mutableSetOf("player")
        var claimCalls = 0
        var notificationCount = 0

        assertFalse(claimPlayerDefeatOnce(isPlaying = false, isDefeated = true) { claimCalls++; true })
        assertFalse(claimPlayerDefeatOnce(isPlaying = true, isDefeated = false) { claimCalls++; true })
        assertEquals(0, claimCalls)

        repeat(2) {
            if (claimPlayerDefeatOnce(isPlaying = true, isDefeated = true) {
                    claimCalls++
                    claimedPlayers.add("new-player")
                }
            ) {
                notificationCount++
            }
        }
        assertEquals(2, claimCalls)
        assertEquals(1, notificationCount)
        assertEquals(setOf("player", "new-player"), claimedPlayers)
    }

    @Test
    fun `desktop network mixins transform the named engine hook sites`() {
        val className = "com/corrodinggames/rts/gameFramework/network/NetworkEngine"
        val classLoader = javaClass.classLoader
        val originalBytes =
            requireNotNull(classLoader.getResourceAsStream("$className.class")) {
                "named game-lib NetworkEngine class is missing from the JVM test classpath"
            }.use { it.readBytes() }

        AsmRegistry.clear()
        try {
            AsmRegistry.register(DesktopRoomOptionHandshakeMixin::class.java)
            AsmRegistry.register(DesktopNetworkPacketMixin::class.java)

            val transformedBytes = AsmProcessor().transform(className, originalBytes, classLoader)
            val transformedNode = ClassNode()
            ClassReader(transformedBytes).accept(transformedNode, ClassReader.EXPAND_FRAMES)

            assertTransformedCall(
                transformedNode,
                "sendPreregisterInfo",
                "io/github/rwpp/mixin/desktop/DesktopRoomOptionHandshakeMixin",
                "includeServerRoomOption",
            )
            assertTransformedCall(
                transformedNode,
                "processSystemPacket",
                "io/github/rwpp/mixin/desktop/DesktopNetworkPacketMixin",
                "receiveRwppPackets",
            )
        } finally {
            AsmRegistry.clear()
        }
    }

    private fun assertTransformedCall(
        classNode: ClassNode,
        targetMethodName: String,
        mixinOwner: String,
        mixinMethodName: String,
    ) {
        val targetMethod = classNode.methods.single { it.name == targetMethodName }
        assertTrue(
            targetMethod.calls(mixinOwner, mixinMethodName).isNotEmpty(),
            "$targetMethodName should invoke $mixinOwner.$mixinMethodName",
        )
    }

    private fun MethodNode.calls(owner: String, name: String): List<MethodInsnNode> =
        instructions.toArray().filterIsInstance<MethodInsnNode>().filter { it.owner == owner && it.name == name }

}
