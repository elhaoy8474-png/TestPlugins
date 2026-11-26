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

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

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

        val episodes = FaselHDParser.parseEpisodes(html, "https://www.faselhd.cc")

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

        val seasons = FaselHDParser.parseSeasons(html, "https://www.faselhd.cc")

        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertEquals(
            listOf("https://www.faselhd.cc/series/show-season-1", "https://www.faselhd.cc/series/show-season-2", "https://www.faselhd.cc/series/show-season-3"),
            seasons.map { it.url }
        )
    }


    @Test
    fun liveSearchMarkupParsesMoviesSeriesAnimeAndSeasonCards() {
        val html = """
            <div class="postDiv">
                <a href="/movies/movie-one"><img alt="فيلم البحث الأول" src="/movie.jpg"></a>
            </div>
            <div class="postDiv">
                <a href="/tvshows/show-one"><img alt="برنامج البحث الأول" src="/show.jpg"></a>
            </div>
            <div class="postDiv">
                <a href="/seasons/series-one"><img alt="مسلسل البحث الأول" src="/series.jpg"></a>
            </div>
            <div class="postDiv">
                <a href="/anime/anime-one"><img alt="أنمي البحث الأول" src="/anime.jpg"></a>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

        assertEquals(4, cards.size)
        assertTrue(cards.any { it.isMovie && it.title == "فيلم البحث الأول" })
        assertTrue(cards.any { !it.isMovie && !it.isAnime && it.url.contains("/tvshows/") })
        assertTrue(cards.any { !it.isMovie && !it.isAnime && it.url.contains("/seasons/") })
        assertTrue(cards.any { it.isAnime && it.title == "أنمي البحث الأول" })
    }

    
    @Test
    fun liveWordPressSearchCardsWithCurrentMarkupAreParsed() {
        val html = """
            <div class="wrapPage">
                <div id="postList">
                    <div class="col-xl-2">
                        <div class="postDiv">
                            <a href="https://www.faselhd.cc/movies/avatar-fire-and-ash">
                                <div class="imgdiv-class">
                                    <img src="https://static.faselhdcdn.com/avatar.jpg"
                                         data-src="https://static.faselhdcdn.com/avatar.jpg"
                                         alt="فيلم Avatar: Fire and Ash 2025 مترجم">
                                </div>
                                <div class="postInner">
                                    <div class="h1">فيلم Avatar: Fire and Ash 2025 مترجم</div>
                                </div>
                            </a>
                        </div>
                    </div>
                    <a href="https://www.faselhd.cc/anime/avatar-season-3">
                        <img src="/avatar-series.jpg" alt="انمي Avatar الموسم الثالث">
                        <div class="h1">انمي Avatar الموسم الثالث</div>
                    </a>
                </div>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

        assertEquals(2, cards.size)
        assertTrue(cards.any { it.isMovie && it.poster == "https://static.faselhdcdn.com/avatar.jpg" })
        assertTrue(cards.any { it.isAnime && it.poster == "https://www.faselhd.cc/avatar-series.jpg" })
    }

    @Test
    fun searchCardUsesTitleOutsideAnchorAndKeepsPoster() {
        val html = """
            <div class="postDiv">
                <a href="/movies/movie-one"><img src="/poster.jpg" alt=""></a>
                <h2 class="h1">عنوان البحث خارج الرابط</h2>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

        assertEquals(1, cards.size)
        assertEquals("عنوان البحث خارج الرابط", cards.single().title)
        assertEquals("https://www.faselhd.cc/poster.jpg", cards.single().poster)
    }


    @Test
    fun currentCatalogCategoriesParseAllMoviesAndRecentAnimeSeries() {
        val html = """
            <div id="postList">
                <div class="postDiv">
                    <a href="/anime-movies/toki-wo-kakeru-shoujo">
                        <img src="/anime-movie.jpg" alt="فيلم Toki wo Kakeru Shoujo">
                    </a>
                    <div class="postInner"><div class="h1">فيلم Toki wo Kakeru Shoujo</div></div>
                </div>
                <div class="postDiv">
                    <a href="/recent_asian/nine-to-six">
                        <img src="/asian.jpg" alt="مسلسل Nine to Six">
                    </a>
                    <div class="postInner"><div class="h1">مسلسل Nine to Six</div></div>
                </div>
                <div class="postDiv">
                    <a href="/recent_anime/hunter-x-hunter">
                        <img src="/anime.jpg" alt="أنمي Hunter x Hunter">
                    </a>
                    <div class="postInner"><div class="h1">أنمي Hunter x Hunter</div></div>
                </div>
                <div class="postDiv">
                    <a href="/episodes/show-episode-1">
                        <img src="/episode.jpg" alt="الحلقة 1">
                    </a>
                    <div class="postInner"><div class="h1">الحلقة 1</div></div>
                </div>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

        assertEquals(3, cards.size)
        assertTrue(cards.any { it.isMovie && it.url.contains("/anime-movies/") })
        assertTrue(cards.any { !it.isMovie && !it.isAnime && it.url.contains("/recent_asian/") })
        assertTrue(cards.any { it.isAnime && it.url.contains("/recent_anime/") })
    }


    @Test
    fun currentAsianSeriesCardsAreRecognizedAndEpisodeFeedsAreIgnored() {
        val html = """
            <div id="postList">
                <div class="postDiv">
                    <a href="/asian_seasons/%D9%85%D8%B3%D9%84%D8%B3%D9%84-shadow-beauty">
                        <div class="imgdiv-class">
                            <img src="/blank.gif" data-src="https://static.faselhdcdn.com/shadow.jpg"
                                 alt="مسلسل Shadow Beauty">
                        </div>
                        <div class="postInner"><div class="h1">مسلسل Shadow Beauty</div></div>
                    </a>
                </div>
                <div class="postDiv">
                    <a href="/asian-episodes/مسلسل-nine-to-six-الحلقة-1">
                        <img src="/episode.jpg" alt="مسلسل Nine to Six – الحلقة 1">
                        <div class="postInner"><div class="h1">مسلسل Nine to Six – الحلقة 1</div></div>
                    </a>
                </div>
                <div class="postDiv">
                    <a href="/anime-episodes/anime-show-episode-8">
                        <img src="/anime-episode.jpg" alt="أنمي الحلقة 8">
                        <div class="postInner"><div class="h1">أنمي الحلقة 8</div></div>
                    </a>
                </div>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

        assertEquals(1, cards.size)
        assertTrue(cards.single().url.contains("/asian_seasons/"))
        assertFalse(cards.single().isMovie)
        assertFalse(cards.single().isAnime)
        assertEquals("https://static.faselhdcdn.com/shadow.jpg", cards.single().poster)
    }

    @Test
    fun currentEpisodeOnlyFeedsDoNotProduceSeriesCards() {
        val html = """
            <div class="postDiv"><a href="/episodes/show-episode-11">
                <img src="/poster.jpg" alt="Show episode 11">
                <div class="h1">Show episode 11</div>
            </a></div>
            <div class="postDiv"><a href="/asian-episodes/asian-show-episode-1">
                <img src="/poster2.jpg" alt="Asian show episode 1">
                <div class="h1">Asian show episode 1</div>
            </a></div>
            <div class="postDiv"><a href="/anime-episodes/anime-show-episode-8">
                <img src="/poster3.jpg" alt="Anime show episode 8">
                <div class="h1">Anime show episode 8</div>
            </a></div>
        """.trimIndent()

        assertTrue(FaselHDParser.parseCards(html, "https://www.faselhd.cc").isEmpty())
    }


    @Test
    fun animeMovieCardsAreClassifiedAsMovies() {
        val html = """
            <div class="postDiv">
                <a href="/anime-movies/film-one">
                    <img src="/anime-movie.jpg" alt="فيلم أنمي">
                    <div class="h1">فيلم أنمي</div>
                </a>
            </div>
        """.trimIndent()

        val cards = FaselHDParser.parseCards(html, "https://www.faselhd.cc")

        assertEquals(1, cards.size)
        assertTrue(cards.single().isMovie)
        assertFalse(cards.single().isAnime)
    }

    @Test
    fun animeEpisodeLinksAreParsedFromAnimeDetailPages() {
        val html = """
            <div id="epAll">
                <a href="/anime-episodes/hunter-episode-10">الحلقة ١٠</a>
                <a href="/anime-episodes/hunter-episode-2">الحلقة ٢</a>
            </div>
        """.trimIndent()

        val episodes = FaselHDParser.parseEpisodes(html, "https://www.faselhd.cc")

        assertEquals(listOf(2, 10), episodes.map { it.number })
        assertTrue(episodes.all { it.url.contains("/anime-episodes/") })
    }

}
