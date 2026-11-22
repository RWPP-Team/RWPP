package io.github.rwpp.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RtsBoxSearchTest {
    @Test
    fun readsBothDirectDownloadFieldsAndDeduplicatesPosts() {
        val resources = assertNotNull(parseRtsBoxSearch("""
            {"code":200,"data":[
              {"title":"模组","downurl":"http://cdn.rtsbox.cn/my_down.php/mod","bbsurl":"https://www.rtsbox.cn/1.html"},
              {"title":"地图","durl":"https://cdn.rtsbox.cn/my_down.php/map","bbsurl":"https://www.rtsbox.cn/2.html"},
              {"title":"重复条目","bbsurl":"https://www.rtsbox.cn/1.html"},
              {"title":"帖子链接","downurl":"javascript:alert(1)"}
            ]}
        """.trimIndent()))

        assertEquals(listOf("模组", "地图", "帖子链接"), resources.map(NetResourceInfo::title))
        assertEquals("https://cdn.rtsbox.cn/my_down.php/mod", resources[0].downloadUrl)
        assertEquals("https://cdn.rtsbox.cn/my_down.php/map", resources[1].downloadUrl)
        assertNull(resources[2].downloadUrl)
    }

    @Test
    fun keepsNativeExtensionsAndSanitizesDownloadNames() {
        assertEquals("地图 1.tmx", bbsResourceFileName("地图 1.TMX", "unused", "tmx"))
        assertEquals("_out_safe.rwmod", bbsResourceFileName("code", "../out/safe.rwmod", "rwmod"))
        assertEquals("resource.tmx", bbsResourceFileName("code", " ... ", "tmx"))
    }

    @Test
    fun distinguishesNoResultsFromMalformedResponses() {
        assertEquals(0, assertNotNull(parseRtsBoxSearch("""{"code":404}""")).size)
        assertNull(parseRtsBoxSearch("<html>invalid</html>"))
    }
}
