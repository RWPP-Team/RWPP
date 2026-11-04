package io.github.rwpp.ui

import android.graphics.RectF
import io.github.rwpp.graphics.WorldUnitMaterialFrameSignature
import io.github.rwpp.graphics.unitMaterialBodyBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldUnitMaterialsTest {
    @Test
    fun airborneExclusionFollowsSpriteAltitudeAndNativeDrawOffset() {
        val nativeBounds = RectF(20f, 60f, 60f, 100f)
        val actual = RectF()
        unitMaterialBodyBounds(nativeBounds, 3f, -2f, 28f, actual)
        fun coordinates(rect: RectF) = listOf(rect.left, rect.top, rect.right, rect.bottom)
        assertEquals(listOf(23f, 30f, 63f, 70f), coordinates(actual))
        assertEquals(
            listOf(20f, 60f, 60f, 100f),
            coordinates(nativeBounds),
            "native shared bounds must remain unchanged"
        )
        unitMaterialBodyBounds(nativeBounds, 0f, 0f, 0f, actual)
        assertEquals(coordinates(nativeBounds), coordinates(actual), "ground buildings keep their native bounds")
    }

    @Test
    fun reusableMaskKeyComparesGeometryValuesAndTextureIdentityExactly() {
        class TextureToken {
            override fun equals(other: Any?) = other is TextureToken
        }

        val texture = TextureToken()
        val first = WorldUnitMaterialFrameSignature().apply {
            putIdentity(texture)
            put(12); put(-4.5f)
        }
        val saved = WorldUnitMaterialFrameSignature().apply { copyFrom(first) }
        assertTrue(first.matches(saved))

        val equalButDifferentTexture = WorldUnitMaterialFrameSignature().apply {
            putIdentity(TextureToken())
            put(12); put(-4.5f)
        }
        assertFalse(first.matches(equalButDifferentTexture), "textures use native identity, not equals")

        equalButDifferentTexture.reset()
        equalButDifferentTexture.putIdentity(texture)
        equalButDifferentTexture.put(12); equalButDifferentTexture.put(-4.0f)
        assertFalse(first.matches(equalButDifferentTexture), "geometry changes invalidate the cached mask")
    }
}
