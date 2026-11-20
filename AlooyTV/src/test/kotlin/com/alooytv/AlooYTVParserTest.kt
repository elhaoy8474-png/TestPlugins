package com.alooytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlooYTVParserTest {
    @Test
    fun catalogCardsReadWatchLinksTitlesAndLazyPosters() {
        val html = """
            <div class="movie-container">
              <div class="col-md-2">
                <div class="latest-movie-img-container">
                  <div class="movie-img">
                    <img class="lazy" src="/blank.jpg" data-src="/uploads/video_thumb/1867.jpg" alt="موسم الرصاص">
                    <a class="ico-play" href="/watch/hijd58ojj179.html"></a>
                    <div class="movie-title"><h3><a href="/watch/hijd58ojj179.html">موسم الرصاص</a></h3></div>
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()

        val cards = AlooYTVParser.parseCards(html, "https://as.alooytv16.com", false)

        assertEquals(1, cards.size)
        assertEquals("موسم الرصاص", cards.single().title)
        assertEquals("https://as.alooytv16.com/watch/hijd58ojj179.html", cards.single().url)
        assertEquals("https://as.alooytv16.com/uploads/video_thumb/1867.jpg", cards.single().poster)
        assertFalse(cards.single().isMovie)
    }

    @Test
    fun episodeLinksPreserveEpisodeKeysAndSortByEpisodeNumber() {
        val html = """
            <div class="season">
              <div class="movie-heading"><span>الموسم الاول</span></div>
              <a class="btn btn-ep" href="/watch/show.html?key=last">Ep#10</a>
              <a class="btn btn-ep" href="/watch/show.html?key=first">Ep#1</a>
              <a class="btn btn-ep" href="/watch/show.html?key=two">Ep#2</a>
            </div>
        """.trimIndent()

        val episodes = AlooYTVParser.parseEpisodes(html, "https://as.alooytv16.com")

        assertEquals(listOf(1, 2, 10), episodes.map { it.number })
        assertEquals("https://as.alooytv16.com/watch/show.html?key=first", episodes.first().url)
        assertEquals(listOf(1, 1, 1), episodes.map { it.season })
    }

    @Test
    fun episodeNumberSupportsArabicNumeralsAndMoviePagesAreRecognized() {
        assertEquals(12, AlooYTVParser.episodeNumber("الحلقة ١٢"))
        assertEquals(3, AlooYTVParser.episodeNumber("Ep#3"))
        val html = "<h1>فيلم تجريبي</h1><video><source src=\"https://media.example/movie.mp4\"></video>"
        assertTrue(AlooYTVParser.parseEpisodes(html, "https://as.alooytv16.com").isEmpty())
        assertEquals("https://media.example/movie.mp4", AlooYTVParser.parseVideoSources(html, "https://as.alooytv16.com").single())
    }

    @Test
    fun videoSourcesIgnoreImageFilesAndBrokenPlaceholderHosts() {
        val html = """
            <video>
              <source src="https://media.example/poster.jpg" type="video/mp4">
              <source src="https://vid8.0" type="video/mp4">
              <source src="https://media.example/good.mp4" type="video/mp4">
            </video>
        """.trimIndent()
        assertEquals(listOf("https://media.example/good.mp4"), AlooYTVParser.parseVideoSources(html, "https://as.alooytv16.com"))
    }

    @Test
    fun movieSearchUrlsKeepTheirTypeMarkerWithoutChangingTheNetworkUrl() {
        val tagged = AlooYTVParser.movieDataUrl("https://as.alooytv16.com/watch/movie.html")
        assertTrue(AlooYTVParser.isMovieDataUrl(tagged))
        assertEquals("https://as.alooytv16.com/watch/movie.html", AlooYTVParser.cleanDataUrl(tagged))
    }

    @Test
    fun videoSourcesAreResolvedAndDeduplicated() {
        val html = """
            <video>
              <source src="https://media.example/episode.mp4" type="video/mp4">
              <source src="https://media.example/episode.mp4" type="video/webm">
              <track kind="subtitles" src="/subs/ar.vtt" srclang="ar" label="Arabic">
            </video>
        """.trimIndent()
        assertEquals(listOf("https://media.example/episode.mp4"), AlooYTVParser.parseVideoSources(html, "https://as.alooytv16.com"))
        assertEquals(1, AlooYTVParser.parseSubtitles(html, "https://as.alooytv16.com").size)
    }
}
