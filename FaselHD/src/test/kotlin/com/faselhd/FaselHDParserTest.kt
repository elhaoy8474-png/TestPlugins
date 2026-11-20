package com.faselhd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaselHDParserTest {
    @Test
    fun catalogParsingKeepsMoviesAndSeriesButIgnoresEpisodePages() {
        val html = """
            <div class="postDiv">
                <a href="/movies/movie-one"><img alt="فيلم مترجم" src="/movie.jpg"></a>
            </div>
            <div class="postDiv">
                <a href="/series/series-one"><img alt="مسلسل الموسم الأول" src="/series.jpg"></a>
            </div>
            <div class="epDivHome">
                <a href="/episodes/episode-one"><img alt="الحلقة 1" src="/episode.jpg"></a>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.fasel-hd.co")

        assertEquals(2, cards.size)
        assertTrue(cards.any { it.isMovie && it.title == "فيلم مترجم" })
        assertTrue(cards.any { !it.isMovie && it.title == "مسلسل الموسم الأول" })
        assertFalse(cards.any { it.url.contains("/episodes/") })
    }

    @Test
    fun episodeParsingSortsNumericallyAndNormalizesArabicDigits() {
        val html = """
            <div id="epAll">
                <a href="/episodes/show-episode-١٠">الحلقة ١٠</a>
                <a href="/episodes/show-episode-٢">الحلقة ٢</a>
                <a href="/episodes/show-episode-١">الحلقة ١</a>
            </div>
        """.trimIndent()

        val episodes = FaselHDParser.parseEpisodes(html, "https://www.fasel-hd.co")

        assertEquals(listOf(1, 2, 10), episodes.map { it.number })
    }

    @Test
    fun seasonParserRecognizesArabicOrdinalSeasonNames() {
        assertEquals(1, FaselHDParser.seasonNumber("مسلسل Tulsa King الموسم الأول"))
        assertEquals(2, FaselHDParser.seasonNumber("مسلسل Tulsa King الموسم الثاني"))
        assertEquals(4, FaselHDParser.seasonNumber("Show Season 4"))
    }

    @Test
    fun seasonParsingExtractsAndSortsAllLinkedSeasons() {
        val html = """
            <div id="seasonList">
                <div class="seasonDiv" onclick="window.location.href = '/series/show-season-3'">
                    <div class="title">موسم 3</div>
                </div>
                <div class="seasonDiv active" onclick="window.location.href = '/series/show-season-1'">
                    <div class="title">موسم 1</div>
                </div>
                <div class="seasonDiv" onclick="window.location.href = '/series/show-season-2'">
                    <div class="title">موسم 2</div>
                </div>
            </div>
        """.trimIndent()

        val seasons = FaselHDParser.parseSeasons(html, "https://www.fasel-hd.co")

        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertEquals(
            listOf("https://www.fasel-hd.co/series/show-season-1", "https://www.fasel-hd.co/series/show-season-2", "https://www.fasel-hd.co/series/show-season-3"),
            seasons.map { it.url }
        )
    }

}
