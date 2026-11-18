package com.cimaclub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class CimaClub : MainAPI() {
    override var mainUrl = "https://w.cimacub.com"
    override var name = "CimaClub"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "المضاف حديثاً",
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبي",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبي",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات آسيوية",
        "$mainUrl/category/مسلسلات-تركية/" to "مسلسلات تركية",
        "$mainUrl/category/افلام-كرتون/" to "أفلام كرتون",
        "$mainUrl/category/مسلسلات-انمي/" to "مسلسلات أنمي",
        "$mainUrl/category/مسلسلات-كرتون/" to "مسلسلات كرتون",
        "$mainUrl/category/برامج-تلفزيونية/" to "برامج تلفزيونية"
    )

    private fun resolveUrl(raw: String): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        return try { URI(mainUrl).resolve(value).toString() } catch (_: Exception) { value }
    }

    private fun imageOf(element: Element): String? {
        val image = element.selectFirst("img") ?: return null
        val raw = image.attr("data-src")
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let(::resolveUrl)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else
            selectFirst("a.recent--block[href]") ?: selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.startsWith("http") && !href.startsWith("/")) return null
        val title = anchor.selectFirst("inner--title h2")?.text()?.trim()
            ?.ifBlank { null }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: anchor.text().trim()
        if (title.isBlank()) return null
        val poster = imageOf(anchor)
        val url = resolveUrl(href)
        val isEpisode = episodeNumber(title) != null || title.contains("الحلقة", true)
        return if (isEpisode) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun extractResults(html: String): List<SearchResponse> {
        val document = Jsoup.parse(html, mainUrl)
        return document.select(
            ".Small--Box a.recent--block[href], a.recent--block[href], .BlocksHolder a[href]"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) request.data else {
            if (base == mainUrl) "$mainUrl/page/$page/"
            else "$base/page/$page/"
        }
        val html = app.get(url, headers = headers).text
        return newHomePageResponse(request.name, extractResults(html))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = app.get("$mainUrl/?s=$encoded", headers = headers).text
        return extractResults(html)
    }

    private fun episodeNumber(value: String): Int? {
        // Do not treat release years (for example 2026) as episode numbers.
        // CimaClub labels episodes explicitly in Arabic or English.
        val match = Regex(
            """(?:الحلقة|حلقة|episode|ep\.?)\s*[-:#]?\s*([0-9٠-٩]+)|S\d{1,2}E(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(value) ?: return null
        val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        val normalized = raw.map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }.joinToString("")
        return normalized.toIntOrNull()
    }

    private fun seriesKey(title: String): String = title
        .replace(Regex("\\s*الحلقة\\s*[0-9٠-٩]+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*حلقة\\s*[0-9٠-٩]+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = headers).text
        val document = Jsoup.parse(html, url)
        val title = document.selectFirst("h1.PostTitle")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore("|").trim()
        if (title.isBlank()) return null

        val posterImage: Element? = document.selectFirst(".MainSingle .image img")
        val imagePoster: String? = posterImage?.let { image ->
            val raw = image.attr("data-src")
                .ifBlank { image.attr("data-original") }
                .ifBlank { image.attr("src") }
            raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let(::resolveUrl)
        }
        val poster: String? = imagePoster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let(::resolveUrl)
        val plot = document.selectFirst(".StoryArea")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val category = document.select(".TaxContent a").firstOrNull()?.text()?.trim().orEmpty()

        val currentEpisode = episodeNumber(title)
        if (currentEpisode != null || title.contains("الحلقة", true)) {
            val key = seriesKey(title)
            val related = document.select(".BlocksHolder a.recent--block[href]")
                .mapNotNull { element ->
                    val label = element.selectFirst("inner--title h2")?.text()?.trim()
                        ?.ifBlank { null } ?: element.attr("title").ifBlank { element.text().trim() }
                    val number = episodeNumber(label) ?: return@mapNotNull null
                    if (seriesKey(label) != key) return@mapNotNull null
                    val href = resolveUrl(element.attr("href"))
                    Triple(number, label, href)
                }
            val episodeItems = (related + Triple(currentEpisode ?: 1, title, url))
                .distinctBy { it.third }
                .sortedBy { it.first }
                .map { (number, label, href) ->
                    newEpisode(href) {
                        name = label
                        episode = number
                        season = 1
                        posterUrl = poster
                    }
                }
            if (episodeItems.isNotEmpty()) {
                val seriesTitle = key.ifBlank { title }
                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodeItems) {
                    posterUrl = poster
                    this.plot = plot
                    tags = if (category.isBlank()) emptyList() else listOf(category)
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
            tags = if (category.isBlank()) emptyList() else listOf(category)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = resolveUrl(data)
        val watchUrl = if (pageUrl.endsWith("/watch") || pageUrl.endsWith("//watch")) {
            pageUrl
        } else {
            pageUrl.trimEnd('/') + "/watch"
        }

        val html = try {
            app.get(watchUrl, headers = headers + mapOf("Referer" to pageUrl)).text
        } catch (_: Exception) {
            return false
        }
        val document = Jsoup.parse(html, watchUrl)
        val servers = linkedSetOf<String>()

        document.select("li[data-watch], [data-watch]").forEach { element ->
            val server = element.attr("data-watch").trim()
            if (server.startsWith("https://") || server.startsWith("http://")) {
                servers.add(server)
            }
        }

        document.select("iframe[src], iframe[data-src], .WatchIframe [src], video[src], source[src]").forEach { element ->
            val server = element.attr("src").ifBlank { element.attr("data-src") }.trim()
            if (server.startsWith("https://") || server.startsWith("http://")) {
                servers.add(server)
            }
        }

        var found = false
        for (server in servers) {
            try {
                if (loadExtractor(server, watchUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
                // Continue to the next publicly listed server.
            }
        }
        return found
    }
}
