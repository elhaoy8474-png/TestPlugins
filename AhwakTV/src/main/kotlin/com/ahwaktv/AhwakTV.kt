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
        "$mainUrl/category.php?cat=mslslat-aseweh" to "مسلسلات آسيوية",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.pm-ul-browse-videos li, .pm-video-thumb, .col-xs-6.col-sm-4.col-md-3, .col-md-3").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val title = a.attr("title").ifBlank { a.text() }.trim()
        if (title.isBlank()) return null
        val href = fixUrl(a.attr("href"))
        val image = this.selectFirst("img")
        val posterUrl = fixUrlNull(image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src"))
        val isSeries = title.contains("مسلسل", true) || title.contains("الحلقة", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search.php?keywords=$query").document
        return document.select("div.pm-ul-browse-videos li, .pm-video-thumb, .col-xs-6.col-sm-4.col-md-3, .col-md-3").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .pm-video-title")?.text()?.trim()
            ?: document.title().substringBefore(" HD").trim().ifBlank { "Unknown" }
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // AhwakTV keeps the video player on play.php, not on watch.php.
        val videoId = Regex("[?&]vid=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        val playerUrl = if (!videoId.isNullOrBlank()) {
            "$mainUrl/play.php?vid=$videoId"
        } else {
            url
        }

        return newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false

        // The actual player iframe is embedded in play.php.
        val iframeUrls = document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            .mapNotNull { iframe ->
                (iframe.attr("src").ifBlank {
                    iframe.attr("data-src").ifBlank { iframe.attr("data-lazy-src") }
                }).takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            }
            .distinct()

        iframeUrls.forEach { iframeUrl ->
            found = true
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        // Some pages expose a direct media URL instead of an iframe.
        document.select("video source[src], source[src], a[href]").forEach { element ->
            val link = element.attr("src").ifBlank { element.attr("href") }
            if (link.contains(".m3u8", true) || link.contains(".mp4", true)) {
                val mediaUrl = fixUrl(link)
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
