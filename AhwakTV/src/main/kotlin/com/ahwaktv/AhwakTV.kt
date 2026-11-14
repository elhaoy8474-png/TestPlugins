package com.ahwaktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AhwakTV : MainAPI() {
    override var mainUrl = "https://ahwaktv.quest"
    override var name = "AhwakTV"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos.php" to "الأحدث",
        "$mainUrl/topvideos.php" to "الأكثر مشاهدة",
        "$mainUrl/movies.php" to "أفلام",
        "$mainUrl/all-series.php" to "مسلسلات",
        "$mainUrl/category.php?cat=msrheat" to "مسرحيات",
        "$mainUrl/category.php?cat=aflam-hnde" to "أفلام هندي",
        "$mainUrl/category.php?cat=aflam-mdbljh" to "أفلام مدبلجة",
        "$mainUrl/category.php?cat=mslslat-aseweh" to "مسلسلات آسيوية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val results = document.select(
            "a[href*='watch.php?vid='], .pm-video-thumb, .pm-ul-browse-videos li"
        ).mapNotNull { element ->
            val card = if (element.tagName() == "a") element.parent() ?: element else element
            card.toSearchResult()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName() == "a" && attr("href").contains("watch.php")) {
            this
        } else {
            selectFirst("a[href*='watch.php?vid=']") ?: selectFirst("a[href]") ?: return null
        }
        val title = link.attr("title").ifBlank { link.text() }.trim()
        if (title.isBlank()) return null
        val href = fixUrl(link.attr("href"))
        val image = selectFirst("img") ?: link.selectFirst("img")
        val poster = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )
        val series = title.contains("مسلسل", true) && !title.contains("الحلقة", true)
        return if (series) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = query.replace(" ", "+")
        val document = app.get("$mainUrl/search.php?keywords=" + encoded).document
        return document.select("a[href*='watch.php?vid='], .pm-video-thumb, .pm-ul-browse-videos li")
            .mapNotNull { element ->
                val card = if (element.tagName() == "a" && element.attr("href").contains("watch.php")) {
                    element.parent() ?: element
                } else element
                card.toSearchResult()
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .pm-video-title")?.text()?.trim()
            ?: document.title().substringBefore(" HD").trim().ifBlank { "Unknown" }
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        val episodeLinks = document.select("a[href*='watch.php?vid=']")
            .filter { it.text().contains("حلقة", true) || it.attr("title").contains("حلقة", true) }
            .distinctBy { it.attr("href") }

        val isEpisodePage = url.contains("watch.php?vid=", true) ||
            title.contains("الحلقة", true) || title.contains("حلقة", true)

        if (!isEpisodePage && episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexed { index, element ->
                val episodeTitle = element.attr("title").ifBlank { element.text() }.trim()
                    .ifBlank { "الحلقة " + (index + 1) }
                val number = Regex("(\\d+)").find(episodeTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(fixUrl(element.attr("href"))) {
                    name = episodeTitle
                    episode = number ?: (index + 1)
                    season = 1
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
            }
        }

        val videoId = Regex("[?&]vid=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        val playerUrl = if (!videoId.isNullOrBlank()) {
            "$mainUrl/play.php?vid=$videoId"
        } else {
            val player = document.selectFirst("iframe[src], iframe[data-src]")
            if (player == null) {
                url
            } else {
                fixUrl(player.attr("src").ifBlank { player.attr("data-src") })
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, referer = "$mainUrl/").document
        var found = false

        val candidates = page.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], a[href]"
        ).mapNotNull { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("href") }
            if (raw.isBlank() || raw.startsWith("javascript:") || raw.startsWith("#")) {
                null
            } else {
                fixUrl(raw)
            }
        }.distinct()

        for (candidate in candidates) {
            val host = candidate.substringAfter("://").substringBefore("/").lowercase()
            val isExternalServer = !host.endsWith("ahwaktv.quest") &&
                !host.endsWith("aahwaktv.beer") &&
                !host.isBlank()
            if (isExternalServer) {
                try {
                    loadExtractor(candidate, data, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {
                    // Try the next server listed on the page.
                }
            }
        }

        page.select("video source[src], video[src], source[src]").forEach { element ->
            val raw = element.attr("src")
            if (raw.contains(".m3u8", true) || raw.contains(".mp4", true)) {
                val media = fixUrl(raw)
                callback(
                    newExtractorLink(
                        name,
                        name,
                        media,
                        if (media.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
