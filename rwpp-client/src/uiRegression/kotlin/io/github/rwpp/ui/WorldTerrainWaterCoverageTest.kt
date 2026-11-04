package io.github.rwpp.ui

import android.graphics.Rect
import com.corrodinggames.rts.game.map.MapTile
import io.github.rwpp.graphics.*
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

class WorldTerrainWaterCoverageTest {
    @Test
    fun semanticClassificationSeparatesSolidWaterShorelinesBridgesAndLava() {
        assertEquals(
            TerrainWaterTileKind.FULL_WATER, terrainWaterTileKind(
                TerrainWaterTileTraits(nativeWater = true, imageName = "flat_grass_atlas.png")
            )
        )
        assertEquals(
            TerrainWaterTileKind.MIXED_SHORELINE, terrainWaterTileKind(
                TerrainWaterTileTraits(
                    nativeWater = true,
                    tileSetName = "longgrass2shallowwater_ridge.tsx",
                    imageName = "longgrass2shallowwater_ridge.png"
                )
            ), "explicit transition artwork must win over a mistaken native full-water flag"
        )
        assertEquals(
            TerrainWaterTileKind.MIXED_SHORELINE, terrainWaterTileKind(
                TerrainWaterTileTraits(waterProperty = true)
            )
        )
        assertEquals(
            TerrainWaterTileKind.MIXED_SHORELINE, terrainWaterTileKind(
                TerrainWaterTileTraits(imageName = "shoreline_water_flat.png")
            )
        )
        assertEquals(
            TerrainWaterTileKind.FULL_WATER, terrainWaterTileKind(
                TerrainWaterTileTraits(imageName = "shallowwater.png")
            ), "a pure native water sheet remains full water even without color inspection"
        )
        assertEquals(
            TerrainWaterTileKind.BRIDGE, terrainWaterTileKind(
                TerrainWaterTileTraits(nativeWater = true, nativeBridge = true, imageName = "waterbridge.png")
            )
        )
        assertEquals(
            TerrainWaterTileKind.BRIDGE, terrainWaterTileKind(
                TerrainWaterTileTraits(bridgeProperty = true, tileSetName = "water_bridge")
            )
        )
        assertEquals(
            TerrainWaterTileKind.LAVA, terrainWaterTileKind(
                TerrainWaterTileTraits(nativeWater = true, nativeLava = true, imageName = "lava.png")
            )
        )
        assertEquals(
            TerrainWaterTileKind.LAND, terrainWaterTileKind(
                TerrainWaterTileTraits(nativeLava = true, cliff = true, imageName = "lava_cliff.png")
            ), "lava cliffs are excluded from the animated lava surface"
        )
        assertEquals(TerrainWaterTileKind.LAND, terrainWaterTileKind(TerrainWaterTileTraits()))
    }

    @Test
    fun solidNativeWaterIgnoresGreyOrGreenArtworkAndHonorsAlpha() {
        val greenPixels = source(4, 4) { _, _ -> argb(255, 50, 130, 72) }
        val sample = rasterizeTerrainTile(TerrainWaterTileKind.FULL_WATER, 4, greenPixels)
        assertAll(sample.waterCoverage, 1f)
        assertAll(sample.opacity, 1f)
        assertAll(sample.lava, 0f)

        val translucentGreen = source(4, 4) { _, _ -> argb(128, 50, 130, 72) }
        val translucent = rasterizeTerrainTile(TerrainWaterTileKind.FULL_WATER, 4, translucentGreen)
        assertAll(translucent.waterCoverage, 1f)
        assertAll(translucent.opacity, 128f / 255f)
    }

    @Test
    fun mixedShorelineSamplesWaterOnlyInsideNamedAtlasAndPreservesColorkeyGaps() {
        val atlas = source(4, 4) { x, _ ->
            when (x) {
                0 -> argb(255, 18, 58, 132) // blue-dominant water
                1 -> argb(255, 42, 132, 55) // opaque land
                2 -> argb(255, 0x00, 0x8a, 0x76) // Tiled trans="008a76"
                else -> argb(128, 18, 58, 132) // antialiased / translucent water
            }
        }
        val sample = rasterizeTerrainTile(TerrainWaterTileKind.MIXED_SHORELINE, 4, atlas)

        assertEquals(1f, sample.waterCoverage[0])
        assertEquals(1f, sample.opacity[0])
        assertEquals(0f, sample.waterCoverage[1], "land pixels in shoreline atlases clear water")
        assertEquals(1f, sample.opacity[1], "opaque land overlays clear water under their pixels")
        assertEquals(0f, sample.waterCoverage[2])
        assertEquals(0f, sample.opacity[2], "the original 008a76 color key must reveal lower layers")
        assertEquals(1f, sample.waterCoverage[3])
        assertTrue(abs(sample.opacity[3] - 128f / 255f) < 0.0001f)
        assertTrue(
            abs(sample.waterCoverage[3] * sample.opacity[3] - 128f / 255f) < 0.0001f,
            "coverage times opacity is the premultiplied water fraction for alpha-over composition"
        )
    }

    @Test
    fun opaqueLandBridgeAndLavaKeepWaterCoverageSeparate() {
        val land = rasterizeTerrainTile(TerrainWaterTileKind.LAND, 4, null)
        assertAll(land.waterCoverage, 0f)
        assertAll(land.opacity, 1f, "an unavailable ordinary land source is conservatively opaque")
        assertAll(land.lava, 0f)

        val bridge = rasterizeTerrainTile(TerrainWaterTileKind.BRIDGE, 4, null)
        assertAll(bridge.waterCoverage, 0f)
        assertAll(bridge.opacity, 1f)
        assertAll(bridge.lava, 0f)

        val lava = rasterizeTerrainTile(TerrainWaterTileKind.LAVA, 4, null)
        assertAll(lava.waterCoverage, 0f)
        assertAll(lava.opacity, 1f)
        assertAll(lava.lava, 1f)

        val transparentTreeOverlay = source(4, 4) { x, _ ->
            if (x == 0) argb(255, 22, 110, 27) else argb(0, 22, 110, 27)
        }
        val tree = rasterizeTerrainTile(TerrainWaterTileKind.LAND, 4, transparentTreeOverlay)
        assertEquals(1f, tree.opacity[0])
        assertEquals(0f, tree.opacity[1], "transparent tree texels must preserve the water below")
        assertEquals(0f, tree.waterCoverage[0])
        assertEquals(0f, tree.waterCoverage[1])
    }

    @Test
    fun adaptiveResolutionAndWeakTileResolutionCacheAreStable() {
        assertEquals(8, terrainWaterSampleResolution(256, 240))
        assertEquals(4, terrainWaterSampleResolution(512, 300))
        assertEquals(2, terrainWaterSampleResolution(1024, 720))
        assertEquals(1, terrainWaterSampleResolution(2048, 1024))

        var traitReads = 0
        var pixelReads = 0
        val tile = syntheticMapTile()
        val sampler = TerrainWaterTileSampler(
            traitsForTile = { traitReads++; TerrainWaterTileTraits(waterProperty = true) },
            pixelsForTile = { pixelReads++; source(4, 4) { _, _ -> argb(255, 18, 58, 132) } }
        )

        val first = sampler.sampleForMap(tile, 256, 240)
        assertSame(first, sampler.sample(tile, 8), "same native tile and resolution reuse the weak-cache sample")
        assertEquals(1, traitReads)
        assertEquals(1, pixelReads)
        val lowerResolution = sampler.sample(tile, 4)
        assertNotSame(first, lowerResolution, "each supported resolution has an independent cached grid")
        assertEquals(2, traitReads)
        assertEquals(2, pixelReads)
    }

    @Test
    fun originalLongGrassRidgeAtlasProducesBothWaterAndOpaqueLandCoverage() {
        val assetRoot = locateGameAssetRoot()
        Assume.assumeTrue("Rusted Warfare terrain assets were not found", assetRoot != null)
        val atlas = ImageIO.read(assetRoot!!.resolve("tilesets/bitmaps/longgrass2shallowwater_ridge.png").toFile())
        val tileSize = 20
        val columns = atlas.width / tileSize
        assertEquals(3, columns)
        assertEquals(0, atlas.width % tileSize)

        // Tile 1 in the game's LongGrass ShallowWater - Ridge.tsx is a real bank with both land
        // and water pixels. Its marked transition name must remain mixed even if a map flags it water.
        val tileIndex = 1
        val left = tileIndex % columns * tileSize
        val top = tileIndex / columns * tileSize
        val rect = Rect(left, top, left + tileSize, top + tileSize)
        val kind = terrainWaterTileKind(
            TerrainWaterTileTraits(
                nativeWater = true,
                tileSetName = "LongGrass ShallowWater - Ridge",
                imageName = "longgrass2shallowwater_ridge.png"
            )
        )
        assertEquals(TerrainWaterTileKind.MIXED_SHORELINE, kind)

        val source = TerrainWaterTilePixelSource(rect) { x, y -> atlas.getRGB(x, y) }
        val sample = rasterizeTerrainTile(kind, resolution = 8, source = source)
        assertTrue(
            sample.waterCoverage.any { it > 0.75f },
            "native ridge tile 1 should retain fully-water sample cells"
        )
        assertTrue(sample.waterCoverage.indices.any {
            sample.waterCoverage[it] < 0.1f && sample.opacity[it] > 0.99f
        }, "native ridge tile 1 should retain opaque land sample cells")
        assertTrue(sample.waterCoverage.all { it in 0f..1f } && sample.opacity.all { it in 0f..1f })
    }

    private fun assertAll(values: FloatArray, expected: Float, message: String = "") {
        for (value in values) assertTrue(abs(value - expected) < 0.0001f, "$message expected=$expected actual=$value")
    }

    private fun source(width: Int, height: Int, pixel: (Int, Int) -> Int): TerrainWaterTilePixelSource =
        TerrainWaterTilePixelSource(Rect(0, 0, width, height), pixel)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun syntheticMapTile(): MapTile {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(MapTile::class.java) as MapTile
    }

    private fun locateGameAssetRoot(): Path? {
        val roots = listOfNotNull(
            System.getProperty("rwpp.gameAssets")?.let { Path.of(it) },
            System.getenv("RWPP_GAME_ASSETS")?.let { Path.of(it) },
            Path.of("D:/steam/steamapps/common/Rusted Warfare/assets"),
            Path.of("D:/Steam/steamapps/common/Rusted Warfare/assets")
        )
        return roots.firstOrNull {
            Files.isRegularFile(it.resolve("tilesets/bitmaps/longgrass2shallowwater_ridge.png"))
        }
    }
}
