package io.github.rwpp.ui

import io.github.rwpp.logic.parseThirdPartyLibraries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThirdPartyLibrariesTest {
    @Test
    fun libraryMetadataLinksLicensesAndKeepsMissingInformationHonest() {
        val libraries = parseThirdPartyLibraries(
            """{"libraries":[
                {"uniqueId":"example:b","name":"Beta","artifactVersion":"2","licenses":["MIT"]},
                {"uniqueId":"example:a","name":"Alpha","licenses":["unknown"]},
                {"uniqueId":"example:c","name":"Gamma"}
            ],"licenses":{"MIT":{"name":"MIT License","url":"https://example.com/mit","content":"License text"}}}"""
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), libraries.map { it.name })
        assertEquals("unknown", libraries[0].licenses.single().name)
        assertEquals("MIT License", libraries[1].licenses.single().name)
        assertEquals("License text", libraries[1].licenses.single().text)
        assertEquals("https://example.com/mit", libraries[1].licenses.single().url)
        assertTrue(libraries[2].licenses.isEmpty())
    }
}
