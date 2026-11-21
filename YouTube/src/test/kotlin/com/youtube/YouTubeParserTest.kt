package com.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeParserTest {
    @Test fun parsesArabicVideoSearchResultsAndChannelNames() {
        val json = """{"contents":{"videoWithContextRenderer":{"videoId":"hZw_e2hPyJw","headline":{"runs":[{"text":"تعليم العربية للناطقين بغيرها"}]},"shortBylineText":{"runs":[{"text":"أتقن العربية | Master Arabic"}]},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/hZw_e2hPyJw/mqdefault.jpg"}]},"lengthText":{"runs":[{"text":"12:36"}]}}}}"""
        val html = "<script>var ytInitialData = '${json.replace("{", "\\\\x7b").replace("}", "\\\\x7d").replace("\"", "\\\\x22")}';</script>"
        val results = YouTubeParser.parseSearchResults(html)
        assertEquals(1, results.size)
        assertEquals("hZw_e2hPyJw", results.single().videoId)
        assertEquals("تعليم العربية للناطقين بغيرها", results.single().title)
        assertEquals("أتقن العربية | Master Arabic", results.single().channel)
        assertEquals("https://i.ytimg.com/vi/hZw_e2hPyJw/mqdefault.jpg", results.single().thumbnail)
    }

    @Test fun ignoresNonVideoRowsAndDeduplicatesRepeatedVideoIds() {
        val json = """{"a":{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Video"}]}}},"b":{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Video"}]}}},"c":{"channelRenderer":{"channelId":"UC123"}}}"""
        val results = YouTubeParser.parseSearchResults("<script>var ytInitialData = '${json.replace("{", "\\\\x7b").replace("}", "\\\\x7d").replace("\"", "\\\\x22")}';</script>")
        assertEquals(1, results.size)
        assertEquals("abcdefghijk", results.single().videoId)
    }

    @Test fun parsesRealYouTubeMobileSearchFixture() {
        val html = javaClass.classLoader!!.getResource("search-fixture.html")!!.readText()
        val results = YouTubeParser.parseSearchResults(html)
        assertTrue("Expected actual public search results", results.size >= 5)
        assertTrue(results.any { it.videoId == "hZw_e2hPyJw" && it.title.contains("العربية") })
        assertTrue(results.any { it.channel?.contains("Master Arabic") == true })
    }

    @Test fun buildsCanonicalWatchUrlsOnlyForValidIds() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", YouTubeParser.watchUrl("abcdefghijk"))
        assertTrue(YouTubeParser.watchUrl("short") == null)
    }
}
