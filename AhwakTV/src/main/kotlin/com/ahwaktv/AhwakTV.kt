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

        val episodes = mutableListOf<Episode>()
        // Keep each season in its own container: episode 1 in season 2 is not a duplicate.
        document.select("div[id^=Season], section[id^=Season], [data-season]").forEachIndexed { index, seasonBox ->
            val seasonId = seasonBox.id().ifBlank { seasonBox.attr("data-season") }
            val season = Regex("\\d+").find(seasonId)?.value?.toIntOrNull() ?: (index + 1)
            seasonBox.select("a[href*='watch.php?vid=']").forEach { element ->
                val href = fixUrl(element.attr("href"))
                val id = Regex("[?&]vid=([a-zA-Z0-9]+)").find(href)?.groupValues?.get(1) ?: return@forEach
                val label = element.attr("title").ifBlank { element.text() }.trim()
                val number = episodeNumber(label) ?: (episodes.count { it.season == season } + 1)
                episodes.add(newEpisode(data = "$mainUrl/play.php?vid=$id") {
                    name = label.ifBlank { "الحلقة $number" }
                    episode = number
                    this.season = season
                    posterUrl = poster
                })
            }
        }
        if (episodes.isEmpty()) {
            val seen = hashSetOf<String>()
            document.select("a[href*='watch.php?vid=']").forEach { element ->
                val href = fixUrl(element.attr("href"))
                val id = Regex("[?&]vid=([a-zA-Z0-9]+)").find(href)?.groupValues?.get(1) ?: return@forEach
                if (!seen.add(id)) return@forEach
                val label = element.attr("title").ifBlank { element.text() }.trim()
                if (!label.contains("حلقة", true) && episodeNumber(label) == null) return@forEach
                val number = episodeNumber(label) ?: (episodes.size + 1)
                episodes.add(newEpisode(data = "$mainUrl/play.php?vid=$id") {
                    name = label.ifBlank { "الحلقة $number" }
                    episode = number
                    season = 1
                    posterUrl = poster
                })
            }
        }
        val uniqueEpisodes = episodes.distinctBy { ${it.season}:${it.episode} }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })
        if (uniqueEpisodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                posterUrl = poster
                plot = description
            }
        }

        return newMovieLoadResponse(pageTitle, url, TvType.Movie, playerUrl(url)) {
            posterUrl = poster
            plot = description
        }
    }


    private fun episodeNumber(label: String): Int? {
        val raw = Regex("[0-9٠-٩]+").find(label)?.value ?: return null
        return raw.map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }.joinToString("").toIntOrNull()
    }

    private fun unpackPackedJs(source: String): String? {
        val eval = Regex("eval\\s*\\(\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}\\s*\\((.*)\\)\\s*\\)",
            setOf(RegexOption.DOT_MATCHES_ALL)).find(source) ?: return null
        val args = Regex("['\\"](.*?)['\\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\\"](.*?)['\\"]\\.split\\s*\\(['\\"]\\|['\\"]\\)",
            setOf(RegexOption.DOT_MATCHES_ALL)).find(eval.groupValues[1]) ?: return null
        val packed = args.groupValues[1]
        val radix = args.groupValues[2].toIntOrNull() ?: return null
        val count = args.groupValues[3].toIntOrNull() ?: return null
        val keys = args.groupValues[4].split('|')
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun base(n0: Int): String {
            if (n0 == 0) return "0"
            var n = n0
            val out = StringBuilder()
            while (n > 0) { out.insert(0, chars[n % radix]); n /= radix }
            return out.toString()
        }
        val map = HashMap<String, String>()
        for (i in 0 until count) keys.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { map[base(i)] = it }
        return Regex("\\b\\w+\\b").replace(packed) { m -> map[m.value] ?: m.value }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("[?&]vid=([a-zA-Z0-9]+)").find(data)?.groupValues?.get(1) ?: return false
        val playUrl = "$mainUrl/play.php?vid=$id"
        val html = app.get(playUrl, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            "Referer" to "$mainUrl/"
        )).text.replace("\\/", "/").replace("&amp;", "&")
        val doc = org.jsoup.Jsoup.parse(html, playUrl)
        val servers = doc.select("li[data-embed]").mapNotNull { item ->
            val embed = item.attr("data-embed").replace("&quot;", "\"").replace("&#39;", "'")
            val src = Regex("""src\s*=\s*['"]([^'"]+)['"]""").find(embed)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val label = item.text().trim().ifBlank { src.substringAfter("://").substringBefore("/") }
            label to fixUrl(src)
        }.distinctBy { it.second }.toMutableList()
        if (servers.isEmpty()) {
            doc.select("iframe[src], iframe[data-src], video[src], source[src]").forEach { el ->
                val src = el.attr("src").ifBlank { el.attr("data-src") }
                if (src.isNotBlank()) servers.add("Server" to fixUrl(src))
            }
        }
        var found = false
        for ((label, link) in servers) {
            try {
                if (loadExtractor(link, playUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
                val embedHtml = app.get(link, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to playUrl
                )).text
                val unpacked = unpackPackedJs(embedHtml) ?: embedHtml
                val file = Regex("""['"]?file['"]?\s*[:=]\s*['"](https?[^'"]+)['"]""")
                    .find(unpacked)?.groupValues?.get(1)
                if (file != null) {
                    val type = when {
                        file.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        file.contains(".mp4", true) -> ExtractorLinkType.VIDEO
                        else -> ExtractorLinkType.INFERRED
                    }
                    callback(newExtractorLink(name, label, file, type) {
                        referer = link
                        quality = Qualities.Unknown.value
                    })
                    found = true
                }
            } catch (_: Exception) {
            }
        }
        return found
    }

}
