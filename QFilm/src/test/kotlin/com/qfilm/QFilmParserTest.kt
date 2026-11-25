package com.qfilm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QFilmParserTest {
    @Test
    fun lazyLoadedPosterPrefersDataEchoOverPlaceholderSrc() {
        val html = """
            <ul class="pm-ul-browse-videos">
                <li>
                    <div class="pm-video-thumb">
                        <a href="/watch.php?vid=one" title="Film One">
                            <img src="/templates/qfilm/img/echo-lzld.png"
                                 data-echo="/uploads/thumbs/one.jpg"
                                 alt="Film One">
                        </a>
                    </div>
                </li>
                <li>
                    <div class="pm-video-thumb">
                        <a href="/watch.php?vid=two">
                            <img src="/uploads/thumbs/two.jpg" alt="Film Two">
                        </a>
                    </div>
                </li>
            </ul>
        """.trimIndent()

        val cards = QFilmParser.parseCards(html, "https://a.qfilm.tv")

        assertEquals(2, cards.size)
        assertEquals("https://a.qfilm.tv/uploads/thumbs/one.jpg", cards[0].poster)
        assertEquals("https://a.qfilm.tv/uploads/thumbs/two.jpg", cards[1].poster)
        assertTrue(cards.none { it.poster?.contains("echo-lzld.png") == true })
    }
}
