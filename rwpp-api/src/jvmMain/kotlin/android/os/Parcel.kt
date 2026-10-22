/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package android.os

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal in-memory primitive parcel used by the game's PointF and RectF implementations. */
class Parcel private constructor() {
    private var buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
    private var size = 0

    private fun writable(bytes: Int) {
        require(bytes >= 0 && buffer.position() <= Int.MAX_VALUE - bytes)
        val required = buffer.position() + bytes
        if (required > buffer.capacity()) {
            val grown = ByteBuffer.allocate(maxOf(required, buffer.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN)
            grown.put(buffer.array(), 0, size)
            grown.position(buffer.position())
            buffer = grown
        }
        size = maxOf(size, required)
    }

    private fun readable(bytes: Int) {
        require(bytes >= 0 && buffer.position() <= size - bytes) { "Parcel read exceeds stored data" }
    }

    fun writeInt(value: Int) {
        writable(Int.SIZE_BYTES)
        buffer.putInt(value)
    }

    fun readInt(): Int {
        readable(Int.SIZE_BYTES)
        return buffer.int
    }

    fun writeFloat(value: Float) = writeInt(value.toRawBits())
    fun readFloat(): Float = Float.fromBits(readInt())
    fun dataPosition(): Int = buffer.position()
    fun dataSize(): Int = size

    fun setDataPosition(position: Int) {
        require(position in 0..size)
        buffer.position(position)
    }

    fun recycle() {
        buffer.clear()
        size = 0
    }

    companion object {
        @JvmStatic
        fun obtain(): Parcel = Parcel()
    }
}
