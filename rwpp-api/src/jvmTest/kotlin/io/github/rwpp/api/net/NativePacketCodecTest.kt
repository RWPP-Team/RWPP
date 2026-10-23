package io.github.rwpp.api.net

import io.github.rwpp.api.net.packets.ModPacket
import io.github.rwpp.api.net.packets.ModPackets
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class NativePacketCodecTest {
    @Test fun modPacketsRoundTripThroughTheSharedNativeCodec() {
        val request = ModPacket.RequestPacket().apply { mods = "mod-a,模组" }
        assertEquals(request.mods, ModPackets.MOD_REQUEST.codec.decode(request.toBytes()).mods)
        val data = ModPacket.ModPackPacket().apply {
            index = 3; name = "模组"; modBytes = ByteArray(128_000) { it.toByte() }
        }
        val received = ModPackets.MOD_DATA.codec.decode(data.toBytes())
        assertEquals(data.index, received.index)
        assertEquals(data.name, received.name)
        assertContentEquals(data.modBytes, received.modBytes)
        assertEquals(502, ModPackets.MOD_READY.codec.decode(ModPacket.ModReloadFinishPacket().toBytes()).type)
    }

    @Test fun modCodecRejectsTruncationAndUnsafeNames() {
        fun frame(name: String, length: Int) = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeInt(0); it.writeUTF(name); it.writeInt(length) }
        }.toByteArray()
        assertFails { ModPackets.MOD_DATA.codec.decode(frame("mod", 50_000_000)) }
        assertFails { ModPackets.MOD_DATA.codec.decode(frame("../outside", 0)) }
        assertFails { ModPackets.MOD_DATA.codec.decode(frame("mod", -1)) }
        assertFails { ModPackets.MOD_READY.codec.decode(byteArrayOf(0, 0, 0, 2)) }
    }
}
