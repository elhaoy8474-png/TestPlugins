package com.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDetailsParserTest {
    @Test fun readsPublicVideoMetadataFromOpenGraphTags() {
        val html = """
            <html><head>
              <meta property="og:title" content="تعلم اللغة العربية | فيديو تعليمي">
              <meta property="og:description" content="شرح مبسط باللغة العربية">
              <meta property="og:image" content="https://i.ytimg.com/vi/0R29gdWqkN8/hqdefault.jpg">
              <link itemprop="name" content="قناة تعليمية">
            </head></html>
        """.trimIndent()
        val details = YouTubeParser.parseDetails(html, "0R29gdWqkN8")
        assertEquals("تعلم اللغة العربية | فيديو تعليمي", details.title)
        assertEquals("شرح مبسط باللغة العربية", details.description)
        assertEquals("https://i.ytimg.com/vi/0R29gdWqkN8/hqdefault.jpg", details.poster)
        assertTrue(details.channel.orEmpty().contains("تعليمية"))
    }
}
