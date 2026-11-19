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
        "$mainUrl/?cimaclub_sort=views" to "الأكثر مشاهدة",
        "$mainUrl/?cimaclub_sort=newMovies" to "جديد الأفلام",
        "$mainUrl/?cimaclub_sort=imdbRating" to "الأعلى تقييماً",

        // Movies by language / origin
        "$mainUrl/category/افلام-عربي/" to "أفلام عربية",
        "$mainUrl/category/افلام-اسيوي/" to "أفلام آسيوية",
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبية",
        "$mainUrl/category/افلام-تركية/" to "أفلام تركية",
        "$mainUrl/category/افلام-هندية/" to "أفلام هندية",
        "$mainUrl/category/افلام-يابانية/" to "أفلام يابانية",

        // Movies by dubbing / animation
        "$mainUrl/category/افلام-اجنبية-مدبلجة/" to "أفلام أجنبية مدبلجة",
        "$mainUrl/category/افلام-مدبلجة/" to "كل الأفلام المدبلجة",
        "$mainUrl/category/افلام-انمي/" to "أفلام أنمي",
        "$mainUrl/category/افلام-كرتون/" to "أفلام كرتون",

        // Series by language / origin
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربية",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبية",
        "$mainUrl/category/مسلسلات-اسيوية/" to "مسلسلات آسيوية",
        "$mainUrl/category/مسلسلات-تركية/" to "مسلسلات تركية",
        "$mainUrl/category/مسلسلات-هندية/" to "مسلسلات هندية",

        // Series by dubbing / subtitles / animation
        "$mainUrl/category/مسلسلات-اسيوية-مدبلجة/" to "مسلسلات آسيوية مدبلجة",
        "$mainUrl/category/مسلسلات-تركية-مترجمة/" to "مسلسلات تركية مترجمة",
        "$mainUrl/category/مسلسلات-تركية-مدبلجة/" to "مسلسلات تركية مدبلجة",
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
        val isSeries = episodeNumber(title) != null ||
            seasonNumber(title) != null ||
            title.contains("الحلقة", true) ||
            title.contains("حلقة", true) ||
            title.contains("episode", true) ||
            title.contains("season", true)

        return if (isSeries) {
            // Preserve the full episode/season label for ranking and parent-show grouping.
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun extractResults(html: String): List<SearchResponse> {
        val document = Jsoup.parse(html, mainUrl)
        val unique = LinkedHashMap<String, SearchResponse>()
        val seriesRanks = HashMap<String, Int>()

        document.select(
            ".Small--Box a.recent--block[href], a.recent--block[href], .BlocksHolder a[href]"
        ).forEach { element ->
            val result = element.toSearchResult() ?: return@forEach

            if (result.type == TvType.TvSeries) {
                // Group episodes from every season into one parent-series card.
                val cleanTitle = seriesKey(result.name)
                val identity = cleanTitle.lowercase()
                    .replace(Regex("""[\p{P}\p{S}\s]+"""), " ")
                    .trim()
                if (identity.isBlank()) return@forEach

                val key = "series:$identity"
                val rank = (seasonNumber(result.name) ?: 1) * 1000 +
                    (episodeNumber(result.name) ?: 0)
                val previousRank = seriesRanks[key] ?: Int.MIN_VALUE

                if (!unique.containsKey(key) || rank > previousRank) {
                    unique[key] = newTvSeriesSearchResponse(
                        cleanTitle,
                        result.url,
                        TvType.TvSeries
                    ) {
                        posterUrl = result.posterUrl
                    }
                    seriesRanks[key] = rank
                }
            } else {
                val identity = resolveUrl(result.url)
                    .substringBefore("?")
                    .trimEnd('/')
                    .lowercase()
                unique.putIfAbsent("url:$identity", result)
            }
        }

        // Keep each show's first position while selecting its newest season/episode link.
        return unique.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sortType = when {
            request.data.contains("cimaclub_sort=views") -> "views"
            request.data.contains("cimaclub_sort=newMovies") -> "newMovies"
            request.data.contains("cimaclub_sort=imdbRating") -> "imdbRating"
            else -> null
        }

        val html = if (sortType != null) {
            // These tabs use the site's own AJAX filtering endpoint.
            // The request type values were verified against the live CimaClub page.
            app.post(
                "$mainUrl/wp-content/themes/cimaclub/Ajaxt/Filtering.php",
                headers = headers + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                data = mapOf("type" to sortType)
            ).text
        } else {
            val base = request.data.substringBefore("?").trimEnd('/')
            val url = if (page <= 1) request.data else {
                if (base == mainUrl) "$mainUrl/page/$page/"
                else "$base/page/$page/"
            }
            app.get(url, headers = headers).text
        }
        return newHomePageResponse(request.name, extractResults(html))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = app.get("$mainUrl/?s=$encoded", headers = headers).text
        return extractResults(html)
    }

    private fun normalizeDigits(value: String): String = value.map {
        if (it in '٠'..'٩') ('0' + (it - '٠')) else it
    }.joinToString("")

    private fun episodeNumber(value: String): Int? {
        val match = Regex(
            """(?:الحلقة|حلقة|episode|ep\.?)\s*[-:#]?\s*([0-9٠-٩]+)|S\d{1,2}E(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(value) ?: return null
        val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return normalizeDigits(raw).toIntOrNull()
    }

    private fun seasonNumber(value: String): Int? {
        val match = Regex(
            """(?:الموسم|موسم|season|الجزء|جزء|part)\s*(?:رقم\s*)?([0-9٠-٩]+|الأول|الاول|الأولى|الاولى|الثاني|الثانية|الثالث|الثالثة|الرابع|الرابعة|الخامس|الخامسة|السادس|السادسة|السابع|السابعة|الثامن|الثامنة|التاسع|التاسعة|العاشر|العاشرة|الحادي\s+عشر|الحادية\s+عشرة|الثاني\s+عشر|الثانية\s+عشرة|الثالث\s+عشر|الثالثة\s+عشرة)""",
            RegexOption.IGNORE_CASE
        ).find(value) ?: return null

        val raw = match.groupValues[1].trim()
        normalizeDigits(raw).toIntOrNull()?.let { return it }
        val ordinal = raw.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace(Regex("""\s+"""), " ")
        return when (ordinal) {
            "الاول", "الاولى" -> 1
            "الثاني", "الثانية" -> 2
            "الثالث", "الثالثة" -> 3
            "الرابع", "الرابعة" -> 4
            "الخامس", "الخامسة" -> 5
            "السادس", "السادسة" -> 6
            "السابع", "السابعة" -> 7
            "الثامن", "الثامنة" -> 8
            "التاسع", "التاسعة" -> 9
            "العاشر", "العاشرة" -> 10
            "الحادي عشر", "الحادية عشرة" -> 11
            "الثاني عشر", "الثانية عشرة" -> 12
            "الثالث عشر", "الثالثة عشرة" -> 13
            else -> null
        }
    }

    private fun seriesKey(title: String): String {
        var cleaned = title
        cleaned = cleaned.replace(
            Regex(
                """\s*(?:الحلقة|حلقة|episode|ep\.?)\s*[-:#]?\s*[0-9٠-٩]+.*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        cleaned = cleaned.replace(
            Regex("""\s*S\d{1,2}E\d+.*$""", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(
            Regex(
                """\s*(?:الموسم|موسم|season|الجزء|جزء|part)\s*(?:رقم\s*)?(?:[0-9٠-٩]+|الأول|الاول|الأولى|الاولى|الثاني|الثانية|الثالث|الثالثة|الرابع|الرابعة|الخامس|الخامسة|السادس|السادسة|السابع|السابعة|الثامن|الثامنة|التاسع|التاسعة|العاشر|العاشرة|الحادي\s+عشر|الحادية\s+عشرة|الثاني\s+عشر|الثانية\s+عشرة|الثالث\s+عشر|الثالثة\s+عشرة).*?$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = resolveUrl(url)
        val html = app.get(pageUrl, headers = headers).text
        val document = Jsoup.parse(html, pageUrl)

        val title = document.selectFirst("h1.PostTitle")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore("|").trim()
        if (title.isBlank()) return null

        val imagePoster = document.selectFirst(".MainSingle .image img")?.let { image ->
            val raw = image.attr("data-src")
                .ifBlank { image.attr("data-original") }
                .ifBlank { image.attr("src") }
            raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let(::resolveUrl)
        }
        val poster = imagePoster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let(::resolveUrl)
        val plot = document.selectFirst(".StoryArea")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
        val category = document.select(".TaxContent a").firstOrNull()?.text()?.trim().orEmpty()

        // CimaClub's real episode catalogue is section.allepcont, not the recommendations grid.
        // On multi-season shows this section can contain the complete cross-season episode list.
        val episodeElements = document.select("section.allepcont a[href]")
        val hasSeasonLinks = document.select("section.otherser a[href]")
            .any { it.attr("href").contains("/series/") }
        val currentEpisode = episodeNumber(title)
        val currentSeason = seasonNumber(title) ?: 1
        val isSeriesPage = episodeElements.isNotEmpty() ||
            currentEpisode != null ||
            seasonNumber(title) != null ||
            hasSeasonLinks

        if (isSeriesPage) {
            val episodesByUrl = LinkedHashMap<String, Triple<Int, Int, Episode>>()

            episodeElements.forEach { element ->
                val episodeUrl = element.attr("href").takeIf { it.isNotBlank() }
                    ?.let(::resolveUrl) ?: return@forEach
                val label = element.attr("title").ifBlank {
                    element.selectFirst(".ep-info h2")?.text()?.trim()
                        ?.ifBlank { null } ?: element.text().trim()
                }
                val episodeNumber = episodeNumber(label)
                    ?: episodeNumber(element.selectFirst(".epnum")?.text().orEmpty())
                    ?: return@forEach
                val episodeSeason = seasonNumber(label) ?: currentSeason
                val episodePoster = imageOf(element) ?: poster
                val displayName = if (
                    label.contains("الأخيرة", true) || label.contains("والأخيرة", true)
                ) "الحلقة $episodeNumber - الأخيرة" else "الحلقة $episodeNumber"

                val episodeItem = newEpisode(episodeUrl) {
                    name = displayName
                    episode = episodeNumber
                    season = episodeSeason
                    posterUrl = episodePoster
                }
                episodesByUrl.putIfAbsent(
                    episodeUrl,
                    Triple(episodeSeason, episodeNumber, episodeItem)
                )
            }

            // Keep the selected episode if the website's catalogue is temporarily empty.
            if (episodesByUrl.isEmpty() && currentEpisode != null) {
                val episodeItem = newEpisode(pageUrl) {
                    name = "الحلقة $currentEpisode"
                    episode = currentEpisode
                    season = currentSeason
                    posterUrl = poster
                }
                episodesByUrl[pageUrl] = Triple(currentSeason, currentEpisode, episodeItem)
            }

            val episodeItems = episodesByUrl.values
                .sortedWith(compareBy<Triple<Int, Int, Episode>> { it.first }.thenBy { it.second })
                .map { it.third }

            if (episodeItems.isNotEmpty()) {
                val seriesTitle = seriesKey(title).ifBlank { title }
                return newTvSeriesLoadResponse(
                    seriesTitle,
                    pageUrl,
                    TvType.TvSeries,
                    episodeItems
                ) {
                    posterUrl = poster
                    this.plot = plot
                    tags = if (category.isBlank()) emptyList() else listOf(category)
                }
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
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
