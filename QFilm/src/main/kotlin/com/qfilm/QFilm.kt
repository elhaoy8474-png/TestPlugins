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
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/category.php?cat=asian-movies" to "أفلام آسيوية"
    )

    private fun Element.toResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else
            selectFirst("a[href*='play.php?vid='], a[href*='watch.php?vid=']") ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("play.php?vid=") && !href.contains("watch.php?vid=")) return null
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }.trim()
        if (title.isBlank()) return null
        val image = anchor.selectFirst("img") ?: selectFirst("img")
        val poster: String? = image?.let {
            it.attr("data-src").ifBlank { it.attr("data-original") }.ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { resolveUrl(it) }
        val url = resolveUrl(href)
        return newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
    }

    private fun extractResults(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        return doc.select("a[href*='play.php?vid='], a[href*='watch.php?vid='], .pm-video-thumb, .video-item, .movie-item, .thumbnail")
            .mapNotNull { it.toResult() }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else {
            val separator = if (request.data.contains("?")) "&" else "?"
            request.data + separator + "page=$page"
        }
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        return newHomePageResponse(request.name, extractResults(html))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = app.get("$mainUrl/search.php?keywords=$encoded", headers = mapOf("Referer" to "$mainUrl/")).text
        return extractResults(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1, .pm-video-title")?.text()?.trim()
            ?: doc.title().substringBefore(" HD").trim()
        val poster: String? = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { resolveUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
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
        val html = app.get(data, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            "Referer" to "$mainUrl/"
        )).text.replace("\\/", "/").replace("&amp;", "&")
        val doc = Jsoup.parse(html, data)
        val servers = linkedSetOf<String>()

        Regex("""<iframe\b[^>]*?src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { servers.add(resolveUrl(it.groupValues[1])) }

        val arrayBody = Regex(
            """var\s+servers\s*=\s*\[(.*?)\]\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.get(1)

        if (!arrayBody.isNullOrBlank()) {
            Regex("""src\s*=\s*\?["'](https?://[^"'\\]+)""", RegexOption.IGNORE_CASE)
                .findAll(arrayBody).forEach { servers.add(it.groupValues[1].replace("\\/", "/")) }
            Regex("""https?://[^"'\s]+""", RegexOption.IGNORE_CASE)
                .findAll(arrayBody).forEach { servers.add(it.value.replace("\\/", "/")) }
        }

        doc.select("[data-embed], .server-btn").forEach { element ->
            val raw = element.attr("data-embed")
            Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.get(1)?.let { servers.add(resolveUrl(it)) }
        }

        var found = false
        for (server in servers.filter { it.startsWith("http://") || it.startsWith("https://") }) {
            try {
                if (loadExtractor(server, data, subtitleCallback, callback)) found = true
            } catch (_: Exception) {
                // Try the next available player.
            }
        }
        return found
    }
}
