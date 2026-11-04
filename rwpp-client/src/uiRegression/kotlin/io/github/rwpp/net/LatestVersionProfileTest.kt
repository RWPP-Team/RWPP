package io.github.rwpp.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LatestVersionProfileTest {
    @Test
    fun readsTheArchiveSizeOfTheSelectedPlatform() {
        val profile = LatestVersionProfile(
            version = "2.4.1",
            body = "Release notes",
            prerelease = false,
            sizes = mapOf("jvm" to 1024L, "android" to 2048L),
        )

        assertEquals(1024L, profile.archiveSize(isAndroid = false))
        assertEquals(2048L, profile.archiveSize(isAndroid = true))
    }

    @Test
    fun reportsAPlatformWithoutArchivesAsUnavailable() {
        val profile = LatestVersionProfile("2.4.1", "", false, mapOf("jvm" to 0L))
        assertNull(profile.archiveSize(isAndroid = false))
        assertNull(profile.archiveSize(isAndroid = true))
    }

    @Test
    fun comparesReleaseVersionsSemanticallyAndDoesNotTreatDowngradesAsUpdates() {
        assertEquals(1, compareReleaseVersions("2.0.0", "v1.6.2"))
        assertEquals(-1, compareReleaseVersions("2.0.0-rc.1", "2.0.0"))
        assertEquals(1, compareReleaseVersions("2.0.0-rc.2", "2.0.0-rc.1"))
        assertEquals(0, compareReleaseVersions("2.0.0+build.3", "v2.0.0+build.4"))
        assertNull(compareReleaseVersions("nightly", "2.0.0"))
    }

    @Test
    fun normalizesReleaseTagsAndRejectsUnsafeText() {
        assertEquals("2.4.1", normalizeReleaseVersion("v2.4.1"))
        assertEquals("2.4.1-rc.1", normalizeReleaseVersion("2.4.1-rc.1"))
        assertFailsWith<IllegalArgumentException> { normalizeReleaseVersion("../2.4.1") }
        assertFailsWith<IllegalArgumentException> { normalizeReleaseVersion("") }
    }
}
