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

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName() == "a" && attr("href").contains("watch.php")) this
        else selectFirst("a[href*='watch.php?vid=']") ?: selectFirst("a[href]") ?: return null
        val title = link.attr("title").ifBlank { link.text() }.trim()
        if (title.isBlank()) return null
        val href = fixUrl(link.attr("href"))
        val image = selectFirst("img") ?: link.selectFirst("img")
        val poster = fixUrlNull(image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src"))
        val isSeries = title.contains("مسلسل", true) && !title.contains("الحلقة", true)
        return if (isSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        } else newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val results = document.select("a[href*='watch.php?vid='], .pm-video-thumb, .pm-ul-browse-videos li")
            .mapNotNull { element ->
                (if (element.tagName() == "a") element.parent() ?: element else element).toSearchResult()
            }.distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search.php?keywords=$encoded").document
        return document.select("a[href*='watch.php?vid='], .pm-video-thumb, .pm-ul-browse-videos li")
            .mapNotNull { element ->
                (if (element.tagName() == "a") element.parent() ?: element else element).toSearchResult()
            }.distinctBy { it.url }
    }

    private fun playerUrl(watchUrl: String): String {
        val id = Regex("[?&]vid=([^&]+)").find(watchUrl)?.groupValues?.getOrNull(1)
        return if (id.isNullOrBlank()) watchUrl else "$mainUrl/play.php?vid=$id"
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val headings = document.select("h1, .pm-video-title")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // AhwakTV puts the episode title first and the series title in a
        // second heading. Prefer the heading that is not an episode title.
        val seriesHeading = headings.firstOrNull {
            !it.contains("الحلقة", true) && !it.contains("حلقة", true)
        }
        val pageTitle = headings.firstOrNull()
            ?: document.title().substringBefore(" HD").trim().ifBlank { "Unknown" }
        val title = seriesHeading ?: pageTitle
            .replace(Regex("\\s+الحلقة\\s+\\d+.*$", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // The site lists episodes as links labelled "1 حلقة", "2 حلقة", etc.
        // Do not classify the whole page as a movie just because its first
        // heading is the title of the currently selected episode.
        val episodeLinks = document.select("a[href*='watch.php'][href*='vid='], a[href*='vid=']")
            .filter { element ->
                val label = element.attr("title").ifBlank { element.text() }.trim()
                label.contains("حلقة", true) ||
                    Regex("^\\s*\\d+\\s*$").containsMatchIn(label)
            }
            .distinctBy { it.attr("href") }

        if (episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexed { index, element ->
                val episodeTitle = element.attr("title").ifBlank { element.text() }.trim()
                    .ifBlank { "الحلقة " + (index + 1) }
                val number = Regex("(\\d+)").find(episodeTitle)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(data = playerUrl(fixUrl(element.attr("href")))) {
                    name = if (episodeTitle.contains("حلقة", true)) episodeTitle
                        else "الحلقة " + (number ?: index + 1)
                    episode = number ?: (index + 1)
                    season = 1
                    posterUrl = poster
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
            }
        }

        return newMovieLoadResponse(pageTitle, url, TvType.Movie, playerUrl(url)) {
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
        val response = app.get(data, referer = "$mainUrl/")
        val page = response.document
        val pageUrl = response.url
        val candidates = linkedMapOf<String, String>()

        fun addCandidate(rawValue: String?, referer: String = pageUrl) {
            val raw = rawValue?.trim()?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.trim('"', '\'', ' ')
                ?: return
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true)) return
            val url = fixUrl(raw)
            if (url.startsWith("http", true)) candidates.putIfAbsent(url, referer)
        }

        // Read the selected player and every server button/link. AhwakTV may
        // keep non-selected servers in data-* attributes or inline handlers.
        page.select("iframe, video, source, a[href], [data-src], [data-url], [data-link], [data-embed], [data-embed-url], [data-video], [data-href], [onclick], [data-server]")
            .forEach { element ->
                listOf(
                    "src", "data-src", "data-lazy-src", "data-url", "data-link",
                    "data-embed", "data-embed-url", "data-video", "data-href", "href"
                ).forEach { key ->
                    if (element.hasAttr(key)) addCandidate(element.attr(key))
                }
                if (element.hasAttr("onclick")) {
                    val handler = element.attr("onclick").replace("\\/", "/")
                    Regex("""https?://[^\s"'<>]+""").findAll(handler).forEach {
                        addCandidate(it.value.trimEnd(',', ';', ')', ']'))
                    }
                }
            }

        // Some player templates store their server URLs only in scripts.
        val html = page.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("&amp;", "&")
        Regex("""https?://[^\s"'<>\\]+""").findAll(html).forEach { match ->
            addCandidate(match.value.trimEnd(',', ';', ')', ']', '}'))
        }
        Regex("""(?:src|file|url|link|embed)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { addCandidate(it.groupValues[1]) }

        var found = false
        val directSeen = hashSetOf<String>()
        for ((candidate, referer) in candidates) {
            if (!directSeen.add(candidate)) continue
            val host = candidate.substringAfter("://").substringBefore("/").lowercase()
            val ownHost = host.endsWith("ahwaktv.quest") || host.endsWith("aahwaktv.beer")
            if (!ownHost && !candidate.contains("ahwaktv", true)) {
                try {
                    loadExtractor(candidate, referer, subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                } catch (_: Exception) {
                    // Continue: a dead server must not block the remaining mirrors.
                }
            } else if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true)) {
                callback(newExtractorLink(
                    name, name, candidate,
                    if (candidate.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    quality = Qualities.Unknown.value
                })
                found = true
            }
        }
        return found
    }
}