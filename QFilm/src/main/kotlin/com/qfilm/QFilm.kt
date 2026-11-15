package com.qfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URI
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class QFilm : MainAPI() {
    override var mainUrl = "https://movie.qfilm.tv"
    override var name = "QFilm"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie)

    private fun resolveUrl(raw: String): String {
        val value = raw.trim().replace("\\/", "/").replace("&amp;", "&")
        return try { URI(mainUrl).resolve(value).toString() } catch (_: Exception) { value }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أفلام - الرئيسية",
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
        val image: Element? = anchor.selectFirst("img") ?: selectFirst("img")
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
