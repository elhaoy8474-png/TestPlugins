package com.qfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URI

class QFilm : MainAPI() {
    override var mainUrl = "https://movie.qfilm.tv"
    override var name = "QFilm"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun resolveUrl(raw: String): String {
        val value = raw.trim().replace("\\/", "/").replace("&amp;", "&")
        return try { URI(mainUrl).resolve(value).toString() } catch (_: Exception) { value }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أفلام - الرئيسية",
        "$mainUrl/category.php?cat=asian-movies" to "أفلام آسيوية",
        "https://a.qfilm.cam/seriestv" to "المسلسلات - الكل",
        "https://a.qfilm.cam/category/مسلسلات-عربية" to "مسلسلات عربية",
        "https://a.qfilm.cam/category/مسلسلات-تركية" to "مسلسلات تركية",
        "https://a.qfilm.cam/category/مسلسلات-اجنبية" to "مسلسلات أجنبية",
        "https://a.qfilm.cam/category/مسلسلات-اسيوية" to "مسلسلات آسيوية",
        "https://a.qfilm.cam/category/مسلسلات-اهواك" to "مسلسلات اهواك",
        "https://a.qfilm.cam/category/مسلسلات-مصرية" to "مسلسلات مصرية",
        "https://a.qfilm.cam/category/مسلسلات-هندية" to "مسلسلات هندية"
    )

    private fun Element.toResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else
            selectFirst("a[href*='play.php?vid='], a[href*='watch.php?vid=']") ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("play.php?vid=") && !href.contains("watch.php?vid=")) return null
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }.trim()
        if (title.isBlank()) return null
        val image: Element? = anchor.selectFirst("img") ?: selectFirst("img")
        val poster: String? = image?.let {
            it.attr("data-src").ifBlank { it.attr("data-original") }.ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { resolveUrl(it) }
        val url = resolveUrl(href)
        return if (title.contains("مسلسل", true) && !title.contains("الحلقة", true)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        } else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
    }

    private fun extractResults(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        return doc.select("a[href*='play.php?vid='], a[href*='watch.php?vid='], .pm-video-thumb, .video-item, .movie-item, .thumbnail")
            .mapNotNull { it.toResult() }.distinctBy { it.url }
    }

    private fun seriesResults(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, "https://a.qfilm.cam")
        val results = linkedMapOf<String, SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (!href.contains("qdrama-") && !href.contains("episode")) return@forEach
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) return@forEach
            val seriesTitle = title.replace(Regex("(?i)\\s*(الموسم\\s*\\d+\\s*)?الحلقة.*$"), "").
                replace(Regex("(?i)\\s*(season\\s*\\d+\\s*)?episode.*$"), "").trim()
            if (seriesTitle.isBlank()) return@forEach
            val image: Element? = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val poster: String? = image?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf { it.isNotBlank() }?.let { try { URI("https://a.qfilm.cam").resolve(it).toString() } catch (_: Exception) { it } }
            val absolute = try { URI("https://a.qfilm.cam").resolve(href).toString() } catch (_: Exception) { href }
            results.putIfAbsent(seriesTitle.lowercase(), newTvSeriesSearchResponse(seriesTitle, absolute, TvType.TvSeries) { posterUrl = poster })
        }
        return results.values.toList()
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else {
            val separator = if (request.data.contains("?")) "&" else "?"
            request.data + separator + "page=$page"
        }
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        return if (request.data.contains("a.qfilm.cam")) {
            newHomePageResponse(request.name, seriesResults(html))
        } else {
            newHomePageResponse(request.name, extractResults(html))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = app.get("$mainUrl/search.php?keywords=$encoded", headers = mapOf("Referer" to "$mainUrl/")).text
        return extractResults(html)
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("a.qfilm.cam")) {
            val episodePage = app.get(url, headers = mapOf("Referer" to "https://a.qfilm.cam/")).text
            val episodeDoc = Jsoup.parse(episodePage, url)
            val seriesUrl = if (url.contains("/series/")) url else episodeDoc.selectFirst("a[href*='/series/']")?.absUrl("href").orEmpty()
            if (seriesUrl.isNotBlank()) {
                val seriesHtml = if (seriesUrl == url) episodePage else app.get(seriesUrl, headers = mapOf("Referer" to url)).text
                val doc = Jsoup.parse(seriesHtml, seriesUrl)
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
                val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                val seen = hashSetOf<String>()
                val episodes = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("href")
                    if (!href.contains("qdrama-")) return@mapNotNull null
                    val episodeUrl = a.absUrl("href").ifBlank { try { URI("https://a.qfilm.cam").resolve(href).toString() } catch (_: Exception) { href } }
                    val label = a.attr("title").ifBlank { a.text() }.trim()
                    if (label.isBlank() || !seen.add(episodeUrl)) return@mapNotNull null
                    episodeUrl to label
                }.mapIndexed { index, item ->
                    val digits = Regex("[0-9٠-٩]+").find(item.second)?.value
                    val number = digits?.map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }?.joinToString("")?.toIntOrNull() ?: index + 1
                    val seasonMatch = Regex("(?i)(?:الموسم|season)\\s*(\\d+)").find(item.second)
                    newEpisode(data = item.first) {
                        name = item.second
                        episode = number
                        season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        posterUrl = poster
                    }
                }
                return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    this.plot = plot
                }
            }
        }
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1, .pm-video-title")?.text()?.trim()
            ?: doc.title().substringBefore(" HD").trim()
        val poster: String? = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { resolveUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val episodes = mutableListOf<Episode>()
        val seen = hashSetOf<String>()
        doc.select("a[href*='watch.php?vid='], a[href*='play.php?vid=']").forEach { element ->
            val episodeUrl = resolveUrl(element.attr("href"))
            val episodeId = Regex("[?&]vid=([^&#]+)", RegexOption.IGNORE_CASE)
                .find(episodeUrl)?.groupValues?.get(1) ?: return@forEach
            if (!seen.add(episodeId)) return@forEach
            val label = element.attr("title").ifBlank { element.text() }.trim()
            if (!label.contains("حلقة", true) && !label.contains("episode", true)) return@forEach
            val numberText = Regex("[0-9٠-٩]+").find(label)?.value
            val number = numberText?.map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }
                ?.joinToString("")?.toIntOrNull() ?: (episodes.size + 1)
            episodes.add(newEpisode(data = episodeUrl) {
                name = label.ifBlank { "الحلقة $number" }
                episode = number
                season = 1
                posterUrl = poster
            })
        }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("a.qfilm.cam")) {
            val cleanUrl = data.substringBefore("?").trimEnd('/')
            val slug = cleanUrl.substringAfterLast('/')
            val playerUrl = if (cleanUrl.contains("/watch/")) cleanUrl + "/servers" else "https://a.qfilm.cam/watch/$slug/servers"
            return try {
                val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to data, "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")).text
                val playerDoc = Jsoup.parse(playerHtml, playerUrl)
                val servers = linkedSetOf<String>()
                playerDoc.select("iframe[src], iframe[data-src], a[href]").forEach { element ->
                    val candidate = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
                    if (candidate.startsWith("http") && !candidate.contains("a.qfilm.cam")) servers.add(candidate)
                }
                var found = false
                servers.forEach { server ->
                    try { if (loadExtractor(server, playerUrl, subtitleCallback, callback)) found = true } catch (_: Exception) { }
                }
                found
            } catch (_: Exception) { false }
        }
        val id = Regex("[?&]vid=([^&#]+)", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.get(1) ?: return false
        val watchUrl = if (data.contains("watch.php", true)) data else "$mainUrl/watch.php?vid=$id"
        // QFilm's player list is rendered by embed.php, not play.php.
        // Try the dedicated embed page first, then legacy player endpoints.
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            "Referer" to watchUrl
        )
        val pageUrls = listOf("$mainUrl/embed.php?vid=$id", "$mainUrl/play.php?vid=$id", watchUrl)
        var rawHtml = ""
        var playerPage = pageUrls.first()
        for (pageUrl in pageUrls) {
            try {
                val page = app.get(pageUrl, headers = headers).text
                if (page.isNotBlank()) {
                    rawHtml = page
                    playerPage = pageUrl
                    if (page.contains("servers", true) || page.contains("iframe", true)) break
                }
            } catch (_: Exception) { }
        }
        if (rawHtml.isBlank()) return false
        // QFilm embeds player iframes inside JavaScript strings. Normalize
        // escaped slashes, quotes and HTML entities before parsing them.
        val html = rawHtml
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        val doc = Jsoup.parse(html, playerPage)
        val servers = linkedSetOf<String>()

        fun addServer(raw: String) {
            val candidate = raw.trim()
                .trim('\'', '"')
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
            if (candidate.startsWith("https://") || candidate.startsWith("http://")) {
                if (candidate != mainUrl && !candidate.startsWith("$mainUrl/")) {
                    servers.add(candidate)
                }
            }
        }

        // Real iframe elements and lazy-loaded iframe attributes.
        doc.select("iframe[src], iframe[data-src], [data-embed]").forEach { element ->
            val value = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-embed") }
            if (value.contains("<iframe", true)) {
                Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(value)?.groupValues?.get(1)?.let(::addServer)
            } else {
                addServer(value)
            }
        }

        // Player lists are commonly stored as escaped iframe HTML in JS arrays.
        val serverArray = Regex(
            """(?:var|let|const)\s+servers\s*=\s*\[(.*?)\]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.get(1).orEmpty()

        val searchSources = listOf(serverArray, html)
        for (source in searchSources) {
            Regex(
                """src\s*=\s*\\?["'](https?://[^"'\s<>]+)""",
                RegexOption.IGNORE_CASE
            ).findAll(source).forEach { addServer(it.groupValues[1]) }

            Regex(
                """https?://[^"'\s<>\\]+""",
                RegexOption.IGNORE_CASE
            ).findAll(source).forEach { addServer(it.value) }
        }

        // Keep order, remove duplicates, and try every discovered player.
        var found = false
        for (server in servers) {
            try {
                if (loadExtractor(server, playerPage, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
                // A broken player must not prevent trying the remaining servers.
            }
        }
        return found
    }
}
