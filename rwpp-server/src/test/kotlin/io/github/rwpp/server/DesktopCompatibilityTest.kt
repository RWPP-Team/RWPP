package io.github.rwpp.server

import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopCompatibilityTest {
    @Test
    fun nativeGeometryRoundTripsWithoutAndroidSdk() {
        val parcel = Parcel.obtain()
        PointF(2.5f, -9f).writeToParcel(parcel, 0)
        RectF(1f, 2f, 30f, 40f).writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        assertEquals(2.5f, parcel.readFloat())
        assertEquals(-9f, parcel.readFloat())
        val rect = RectF.e.createFromParcel(parcel) as RectF
        assertEquals(1f, rect.left)
        assertEquals(2f, rect.top)
        assertEquals(30f, rect.right)
        assertEquals(40f, rect.bottom)
        assertEquals(parcel.dataSize(), parcel.dataPosition())
    }

    @Test
    fun parcelGrowthAndOverwritesPreserveStoredData() {
        val parcel = Parcel.obtain()
        repeat(100) { parcel.writeInt(it) }
        parcel.setDataPosition(4)
        parcel.writeInt(-123)
        assertEquals(400, parcel.dataSize())
        parcel.setDataPosition(0)
        repeat(100) { assertEquals(if (it == 1) -123 else it, parcel.readInt()) }
        assertFailsWith<IllegalArgumentException> { parcel.readInt() }
        assertFailsWith<IllegalArgumentException> { parcel.setDataPosition(-1) }
        assertFailsWith<IllegalArgumentException> { parcel.setDataPosition(401) }
        parcel.recycle()
        assertEquals(0, parcel.dataSize())
        assertFailsWith<IllegalArgumentException> { parcel.readFloat() }
    }
}
