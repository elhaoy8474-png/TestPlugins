package com.alooytv

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder

class AlooYTV : MainAPI() {
    override var mainUrl = "https://aloooytv.com"
    override var name = "AlooYTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val officialUrl = "https://aloooytv.com"
    @Volatile private var activeDomain: String? = null
    @Volatile private var cachedMovieUrls: Set<String>? = null
    private val mapper = ObjectMapper()

    private data class Section(val title: String, val path: String, val isMovie: Boolean = false)

    private val sections = listOf(
        Section("أحدث الحلقات", "/tv-series.html"),
        Section("مسلسلات خليجية", "/genre/kleeji.html"),
        Section("مسلسلات عربية", "/genre/arabic.html"),
        Section("مسلسلات تركية", "/genre/turki.html"),
        Section("مسلسلات فارسية", "/genre/farisi.html"),
        Section("رمضان خليجي 2026", "/genre/ramadan-kleeji-2026.html"),
        Section("رمضان عربي 2026", "/genre/ramadan-arabi-2026.html"),
        Section("أفلام عربية", "/genre/arabic-movies.html", true)
    )

    override val mainPage = mainPageOf(
        "$officialUrl/go/series" to "أحدث الحلقات",
        "$officialUrl/go/gulf" to "مسلسلات خليجية",
        "$officialUrl/go/arabic" to "مسلسلات عربية",
        "$officialUrl/go/turkish" to "مسلسلات تركية",
        "$officialUrl/go/series?section=persian" to "مسلسلات فارسية",
        "$officialUrl/go/series?section=ramadan-gulf" to "رمضان خليجي 2026",
        "$officialUrl/go/series?section=ramadan-arabic" to "رمضان عربي 2026",
        "$officialUrl/go/series?section=movies" to "أفلام عربية"
    )

    private suspend fun getActiveDomain(): String {
        activeDomain?.let { return it }
        val response = app.get("$officialUrl/go/series")
        val finalUrl = response.url
        val uri = URI(finalUrl)
        val host = uri.host ?: throw ErrorLoadingException("تعذر تحديد نطاق AlooYTV الحالي")
        if (host != "aloooytv.com" && !host.endsWith(".alooytv16.com")) {
            throw ErrorLoadingException("نطاق المشاهدة الحالي غير متوقع")
        }
        val domain = "${uri.scheme}://$host"
        activeDomain = domain
        mainUrl = domain
        return domain
    }

    private fun sectionPath(section: Section, page: Int): String {
        if (page <= 1) return section.path
        return if (section.path == "/tv-series.html") {
            "/tvseries/home/${(page - 1) * 24}.html"
        } else {
            val slug = section.path.removePrefix("/genre/").removeSuffix(".html")
            "/genre/$slug/${(page - 1) * 50}.html"
        }
    }

    private fun AlooYTVParser.Card.toResponse(): SearchResponse = if (isMovie) {
        newMovieSearchResponse(title, AlooYTVParser.movieDataUrl(url), TvType.Movie) { posterUrl = poster }
    } else {
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val domain = getActiveDomain()
        val section = sections.firstOrNull { it.title == request.name }
            ?: return newHomePageResponse(request.name, emptyList(), false)
        val path = sectionPath(section, page)
        val html = try {
            app.get("$domain$path").text
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        val items = AlooYTVParser.parseCards(html, domain, section.isMovie).map { it.toResponse() }
        val nextPath = sectionPath(section, page + 1)
        val hasNext = AlooYTVParser.hasNextPage(html, domain, nextPath)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val domain = getActiveDomain()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = app.get("$domain/home/autocompleteajax?term=$encoded")
        val root = mapper.readTree(response.text)
        if (!root.isArray) return emptyList()
        val movieUrls = try {
            cachedMovieUrls ?: AlooYTVParser.parseCards(
                app.get("$domain/genre/arabic-movies.html").text, domain, true
            ).map { it.url }.toSet().also { cachedMovieUrls = it }
        } catch (_: Exception) {
            cachedMovieUrls.orEmpty()
        }
        return root.mapNotNull { item ->
            val title = item.path("title").asText("").trim()
            val rawUrl = item.path("url").asText("").trim()
            if (title.isBlank() || rawUrl.isBlank()) return@mapNotNull null
            val url = AlooYTVParser.absoluteUrl(domain, rawUrl)
            val poster = item.path("image").asText("").takeIf { it.isNotBlank() }
                ?.let { AlooYTVParser.absoluteUrl(domain, it) }
            val isMovie = item.path("type").asText("").contains("movie", true) ||
                url in movieUrls || title.startsWith("فلم ") || title.startsWith("فيلم ")
            if (isMovie) newMovieSearchResponse(title, AlooYTVParser.movieDataUrl(url), TvType.Movie) { posterUrl = poster }
            else newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val domain = getActiveDomain()
        val isMovie = AlooYTVParser.isMovieDataUrl(url)
        val watchUrl = AlooYTVParser.cleanDataUrl(url)
        val document = app.get(watchUrl).document
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = document.selectFirst(".tab-content .col-md-3 img")?.let { image ->
            image.attr("abs:src").ifBlank { image.attr("abs:data-src") }
        }?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { AlooYTVParser.absoluteUrl(domain, it) }
        val plot = document.selectFirst(".tab-content .col-md-9 p")?.text()?.trim()
        val episodes = AlooYTVParser.parseEpisodes(document.html(), domain)

        return if (!isMovie && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, watchUrl, TvType.TvSeries, episodes.map { episode ->
                newEpisode(episode.url) {
                    name = episode.title
                    this.episode = episode.number
                    this.season = episode.season
                    posterUrl = poster
                }
            }) {
                posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val domain = getActiveDomain()
        val html = app.get(data, headers = mapOf("Referer" to "$domain/")).text
        AlooYTVParser.parseSubtitles(html, domain).forEach { subtitle ->
            subtitleCallback(SubtitleFile(subtitle.language, subtitle.url))
        }
        val links = AlooYTVParser.parseVideoSources(html, domain)
        links.forEach { videoUrl ->
            callback.invoke(
                newExtractorLink(name, "AlooYTV MP4", videoUrl) {
                    referer = "$domain/"
                    quality = Qualities.Unknown.value
                }
            )
        }
        return links.isNotEmpty()
    }
}
