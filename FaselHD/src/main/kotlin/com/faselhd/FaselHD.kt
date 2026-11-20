package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

class FaselHD : MainAPI() {
    override var mainUrl = "https://www.fasel-hd.co"
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
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/main" to "الرئيسية",
        "$mainUrl/movies" to "أفلام أجنبية",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/asian-series" to "مسلسلات آسيوية",
        "$mainUrl/dubbed-movies" to "أفلام مدبلجة",
        "$mainUrl/hindi" to "أفلام هندية",
        "$mainUrl/anime" to "أنمي"
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
            app.get(url, headers = webHeaders).text
        } catch (_: Exception) {
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
            return emptyList()
        }
        return parseCards(html)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query)

    private fun isMovieUrl(url: String): Boolean {
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { url.lowercase() }
        return path.startsWith("/movies/") ||
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

        val season = FaselHDParser.seasonNumber(title)
        val episodes = FaselHDParser.parseEpisodes(html, url).map { item ->
            newEpisode(item.url) {
                name = item.title
                episode = item.number
                season = season
            }
        }

        val type = if (isAnimeUrl(url)) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = poster
            backgroundPosterUrl = background
            this.plot = plot
        }
    }

    private fun qualityValue(label: String): Int {
        val number = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        return when (number) {
            2160 -> Qualities.P2160.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun collectSubtitles(
        playerHtml: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val document = Jsoup.parse(playerHtml, playerUrl)
        val found = linkedSetOf<String>()

        document.select("track[src], track[data-src], [data-subtitle]").forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-subtitle") }
            val subtitleUrl = absoluteUrl(raw)
            val language = element.attr("srclang")
                .ifBlank { element.attr("label") }
            val isArabic = language.startsWith("ar", true) ||
                language.contains("arab", true) ||
                language.contains("عربي")
            if (isArabic && (subtitleUrl.contains(".vtt", true) || subtitleUrl.contains(".srt", true))) {
                if (found.add(subtitleUrl)) {
                    subtitleCallback(newSubtitleFile("Arabic", subtitleUrl))
                }
            }
        }

    }

    private suspend fun extractPlayerLinks(
        playerUrl: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerHtml = try {
            app.get(
                playerUrl,
                headers = webHeaders + mapOf("Referer" to episodeUrl)
            ).text
        } catch (_: Exception) {
            return false
        }
        if (playerHtml.isBlank()) return false

        collectSubtitles(playerHtml, playerUrl, subtitleCallback)

        val playerDocument = Jsoup.parse(playerHtml, playerUrl)
        val buttons = playerDocument.select(".hd_btn[data-url], button[data-url*='.m3u8']")
        if (buttons.isEmpty()) return false

        val sources = buttons.mapNotNull { button ->
            val raw = button.attr("data-url").trim()
            if (!raw.contains(".m3u8", true)) return@mapNotNull null
            val streamUrl = absoluteUrl(raw)
            if (!streamUrl.startsWith("https://")) return@mapNotNull null
            streamUrl to button.text().trim().ifBlank { "Auto" }
        }.distinctBy { it.first }

        if (sources.isEmpty()) return false
        val autoSource = sources.firstOrNull {
            it.second.contains("auto", true) || it.second.contains("تلقائي")
        }
        val selectedSources = if (autoSource != null) listOf(autoSource) else sources
        val streamHeaders = mapOf(
            "User-Agent" to desktopUa,
            "Referer" to playerUrl,
            "Origin" to mainUrl
        )

        var found = false
        for ((streamUrl, label) in selectedSources) {
            try {
                val masterText = app.get(streamUrl, headers = streamHeaders).text
                val subtitleLines = Regex(
                    """#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*""",
                    RegexOption.IGNORE_CASE
                ).findAll(masterText)
                subtitleLines.forEach { line ->
                    val text = line.value
                    val language = Regex("""LANGUAGE="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1).orEmpty()
                    val subtitleName = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1).orEmpty()
                    val uri = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1).orEmpty()
                    if (uri.isNotBlank() && (language.startsWith("ar", true) ||
                                subtitleName.contains("arab", true) || subtitleName.contains("عربي"))) {
                        subtitleCallback(newSubtitleFile("Arabic", absoluteUrl(uri)))
                    }
                }
            } catch (_: Exception) {
                // The player may still expose usable variants through its quality buttons.
            }

            try {
                val generated = M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    playerUrl,
                    headers = streamHeaders
                )
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                    found = true
                } else {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - $label",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = playerUrl
                            quality = qualityValue(label)
                            headers = streamHeaders
                        }
                    )
                    found = true
                }
            } catch (_: Exception) {
                // Continue with any remaining publicly listed quality links.
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeHtml = try {
            app.get(data, headers = webHeaders).text
        } catch (_: Exception) {
            return false
        }
        val document = Jsoup.parse(episodeHtml, data)
        val playerUrls = linkedSetOf<String>()
        document.select(
            "[name=player_iframe][data-src], iframe[src], iframe[data-src], [data-src*='video_player']"
        ).forEach { element ->
            val raw = element.attr("data-src")
                .ifBlank { element.attr("src") }
                .trim()
            if (raw.isBlank()) return@forEach
            val playerUrl = absoluteUrl(raw)
            if (playerUrl.startsWith(mainUrl) && playerUrl.contains("video_player")) {
                playerUrls.add(playerUrl)
            }
        }

        var found = false
        for (playerUrl in playerUrls) {
            if (extractPlayerLinks(playerUrl, data, subtitleCallback, callback)) {
                found = true
            }
        }

        if (!found) {
            val externalFrames = document.select("iframe[src], iframe[data-src]").mapNotNull { frame ->
                val raw = frame.attr("src").ifBlank { frame.attr("data-src") }.trim()
                if (raw.isBlank()) return@mapNotNull null
                val frameUrl = absoluteUrl(raw)
                frameUrl.takeIf { it.startsWith("http") && !it.startsWith(mainUrl) }
            }.distinct()

            for (frameUrl in externalFrames) {
                try {
                    if (loadExtractor(frameUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (_: Exception) {
                    // Keep trying the remaining publicly exposed player frames.
                }
            }
        }
        return found
    }
}
