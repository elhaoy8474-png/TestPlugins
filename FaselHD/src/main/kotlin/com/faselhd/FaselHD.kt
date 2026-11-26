package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

class FaselHD : MainAPI() {
    override var mainUrl = "https://www.faselhd.cc"
    override var name = "FaselHD"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val desktopUa =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val webHeaders = mapOf(
        "User-Agent" to desktopUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    // The site redirects the bare host to www. Try both entry hosts because
    // some Android network stacks cache or mishandle the canonical redirect.
    private fun alternateHostUrl(url: String): String =
        url.replaceFirst("https://www.faselhd.cc", "https://faselhd.cc")

    private suspend fun fetchCatalogPage(url: String): String {
        val candidates = listOf(url, alternateHostUrl(url)).distinct()
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val html = app.get(
                    candidate,
                    headers = webHeaders + mapOf("Referer" to "$mainUrl/")
                ).text
                if (html.contains("postDiv") || html.contains("slideContent") || html.contains("id=\"postList\"")) {
                    return html
                }
                // A challenge/error page is not a valid empty catalog.
                lastError = IllegalStateException("FaselHD returned an unrecognized page for $candidate")
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Unable to load FaselHD catalog: $url")
    }

    // Use only real catalog pages that contain series/movie cards.
    // The "recent" feeds are episode pages, not series listings, so they
    // intentionally stay out of the home catalog.
    override val mainPage = mainPageOf(
        "$mainUrl/main" to "أحدث المحتوى",
        "$mainUrl/movies" to "أفلام أجنبية",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime-movies" to "أفلام أنمي",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/tvshows" to "برامج",
        "$mainUrl/recent_tvshows" to "أحدث البرامج"
    )

    private fun absoluteUrl(raw: String): String =
        FaselHDParser.absoluteUrl(mainUrl, raw)

    private fun cardResponse(card: FaselHDParser.Card): SearchResponse {
        return when {
            card.isMovie -> newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                posterUrl = card.poster
            }
            card.isAnime -> newAnimeSearchResponse(card.title, card.url, TvType.Anime) {
                posterUrl = card.poster
            }
            else -> newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                posterUrl = card.poster
            }
        }
    }

    private fun parseCards(html: String): List<SearchResponse> =
        FaselHDParser.parseCards(html, mainUrl).map(::cardResponse)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) request.data else "$base/page/$page"
        val html = try {
            fetchCatalogPage(url)
        } catch (error: Exception) {
            // Do not disguise transport/challenge failures as a valid empty page.
            logError(error)
            return newHomePageResponse(request.name, emptyList(), false)
        }
        val document = Jsoup.parse(html, url)
        val cards = parseCards(html)
        val nextPage = document.select("a[href*='/page/" + (page + 1) + "']").isNotEmpty()
        return newHomePageResponse(request.name, cards, nextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val html = try {
            app.get(url, headers = webHeaders).text
        } catch (_: Exception) {
            ""
        }
        val pageResults = parseCards(html)
        if (pageResults.isNotEmpty()) return pageResults

        // The primary cs-ar reference uses FaselHD's public WordPress live-search
        // action when the normal ?s= page has no result cards.
        return try {
            val ajaxHtml = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = webHeaders + mapOf("Referer" to url),
                data = mapOf("action" to "dtc_live", "trsearch" to query)
            ).text
            parseCards(ajaxHtml)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query)

    private fun isMovieUrl(url: String): Boolean {
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { url.lowercase() }
        return path.startsWith("/movies/") ||
            path.startsWith("/anime-movies/") ||
            path.startsWith("/asian-movies/") ||
            path.startsWith("/anime-movies/") ||
            path.startsWith("/dubbed-movies/") ||
            path.startsWith("/hindi/")
    }

    private fun isAnimeUrl(url: String): Boolean {
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { url.lowercase() }
        return path.startsWith("/anime/")
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = try {
            app.get(url, headers = webHeaders).text
        } catch (_: Exception) {
            return null
        }
        val document = Jsoup.parse(html, url)
        val title = document.selectFirst(".singleInfo .title, h1.h1.title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").trim()
        if (title.isBlank()) return null

        val poster = document.selectFirst(".posterImg img, img.poster, meta[property=og:image]")
            ?.let { element ->
                if (element.tagName() == "meta") element.attr("content")
                else element.attr("data-src").ifBlank { element.attr("src") }
            }
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)

        val plot = document.selectFirst(".singleDesc, meta[name=description], meta[property=og:description]")
            ?.let { element ->
                if (element.tagName() == "meta") element.attr("content") else element.text()
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val background = document.selectFirst(".singlePage")?.attr("style")
            ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)

        if (isMovieUrl(url)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = background
                this.plot = plot
            }
        }

        val seasonPages = linkedMapOf<String, Int>()
        seasonPages[url] = FaselHDParser.seasonNumber(title)
        FaselHDParser.parseSeasons(html, url).forEach { season ->
            seasonPages[season.url] = season.number
        }

        val parsedEpisodes = linkedMapOf<String, Episode>()
        for ((seasonUrl, seasonNumber) in seasonPages) {
            val seasonHtml = if (seasonUrl == url) html else try {
                app.get(seasonUrl, headers = webHeaders).text
            } catch (_: Exception) {
                continue
            }
            FaselHDParser.parseEpisodes(seasonHtml, seasonUrl).forEach { item ->
                parsedEpisodes.putIfAbsent(item.url, newEpisode(item.url) {
                    name = item.title
                    episode = item.number
                    this.season = seasonNumber
                })
            }
        }
        val episodes = parsedEpisodes.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )

        val type = if (isAnimeUrl(url)) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = poster
            backgroundPosterUrl = background
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = FaselHDPlayback(
            mainUrl, name, desktopUa, webHeaders,
            fetch = { url, headers -> app.get(url, headers = headers).text },
            externalLoad = { url, referer, subtitles, links ->
                loadExtractor(url, referer, subtitles, links)
            }
        )
        return playback.loadLinks(data, subtitleCallback, callback)
    }

}
