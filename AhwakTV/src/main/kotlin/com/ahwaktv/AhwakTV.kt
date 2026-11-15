package com.ahwaktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AhwakTV : MainAPI() {
    override var name = "AhwakTV"
    override var mainUrl = "https://yam.ahwaktv.net"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    private val headers = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/moslslat.php" to "المسلسلات",
        "$mainUrl/category.php" to "التصنيفات",
        "$mainUrl/category.php?cat=aflam-arabya1" to "أفلام عربي",
        "$mainUrl/category.php?cat=moslslat-ahwak" to "مسلسلات أهواك",
        "$mainUrl/newvideos.php" to "الجديد",
        "$mainUrl/topvideos.php" to "الأكثر مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val source = if (isCategoryIndex(request.data)) mainUrl else request.data
        var doc = app.get(source, headers = headers).document
        if (page > 1 && !isCategoryIndex(request.data)) {
            for (p in 2..page) {
                val next = pageUrl(doc, p) ?: nextUrl(doc) ?: break
                if (next == doc.location()) break
                doc = app.get(next, headers = headers, referer = doc.location()).document
            }
        }
        val items = if (isCategoryIndex(request.data)) categories(doc) else media(doc, categoryType(request.data))
        return newHomePageResponse(request, items, !isCategoryIndex(request.data) && hasNext(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val home = app.get(mainUrl, headers = headers).document
        val form = home.select("form").firstOrNull { searchInput(it) != null } ?: return null
        val input = searchInput(form) ?: return null
        val field = input.attr("name").takeIf { it.isNotBlank() } ?: return null
        val action = form.absUrl("action").ifBlank { mainUrl }
        if (!siteUrl(action)) return null
        val values = linkedMapOf<String, String>()
        form.select("input[name]").forEach {
            if (it.attr("type").lowercase() !in setOf("submit", "button", "password", "file", "hidden", "checkbox", "radio")) {
                values[it.attr("name")] = it.attr("value")
            }
        }
        form.select("input[type=hidden][name]").forEach { values[it.attr("name")] = it.attr("value") }
        values[field] = query
        var doc = if (form.attr("method").equals("post", true)) {
            app.post(action, headers = headers, data = values, referer = mainUrl).document
        } else {
            app.get(queryUrl(action, values), headers = headers, referer = mainUrl).document
        }
        if (page > 1) for (p in 2..page) {
            val next = pageUrl(doc, p) ?: nextUrl(doc) ?: break
            if (next == doc.location()) break
            doc = app.get(next, headers = headers, referer = doc.location()).document
        }
        return newSearchResponseList(media(doc), hasNext(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, referer = mainUrl).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" - ").trim().ifBlank { "AhwakTV" }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { image -> if (image.startsWith("http")) image else mainUrl + "/" + image.trimStart('/') }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val path = url.substringBefore('?').substringAfterLast('/')
        val isCategory = path == "category.php" && url.substringAfter('?', "").contains("cat=")

        if (isCategory) {
            val kind = categoryType(url, title) ?: TvType.TvSeries
            val mediaResults = media(doc, kind)
            val subcategories = relatedCategories(doc)

            // A category that contains one series (for example "Friends - all seasons")
            // is treated as a series collection, not as a generic category page.
            if (kind != TvType.Movie && subcategories.isEmpty() && mediaResults.size == 1) {
                val seriesName = mediaResults.first().name
                val extraDocs = categoryPages(url, doc)
                val allEpisodes = episodes(doc, poster, seriesName, extraDocs = extraDocs)
                if (allEpisodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(seriesName, url, kind, allEpisodes) {
                        posterUrl = poster
                        this.plot = plot
                    }
                }
            }

            val results = (subcategories + mediaResults).distinctBy { it.url }
            return if (kind == TvType.Movie) {
                newMovieLoadResponse(title, url, TvType.Movie, "") {
                    posterUrl = poster
                    this.plot = plot
                    recommendations = results
                }
            } else {
                newTvSeriesLoadResponse(title, url, kind, emptyList()) {
                    posterUrl = poster
                    this.plot = plot
                    recommendations = results
                }
            }
        }

        if (path == "view-serie.php") {
            val kind = typeOf(title, TvType.TvSeries) ?: TvType.TvSeries
            val episodeList = episodes(doc, poster, title)
            return newTvSeriesLoadResponse(title, url, kind, episodeList) {
                posterUrl = poster
                this.plot = plot
            }
        }

        if (path == "watch.php" && (hasEpisodeSection(doc) || isSeriesTitle(title))) {
            val seriesName = seriesTitleFromPage(doc, title)
            val kind = typeOf(seriesName, typeOf(title, TvType.TvSeries) ?: TvType.TvSeries)
                ?: TvType.TvSeries
            val episodeList = episodes(doc, poster, seriesName, url)
            return newTvSeriesLoadResponse(seriesName, url, kind, episodeList) {
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
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!http(data)) return false

        val doc = app.get(data, headers = headers, referer = mainUrl).document
        val candidates = linkedMapOf<String, String>()
        doc.select("iframe[src], iframe[data-src], video[src], video[data-src], video source[src], source[src]")
            .forEachIndexed { i, el ->
                val src = absolute(el, "src").ifBlank { absolute(el, "data-src") }
                if (http(src)) candidates.putIfAbsent(src, el.attr("title").ifBlank { "Server ${i + 1}" })
            }
        doc.select(".server a[href], .servers a[href], .embed_server a[href], .player a[href], [data-server] a[href]")
            .forEachIndexed { i, el ->
                val src = el.absUrl("href")
                if (http(src)) candidates.putIfAbsent(src, el.text().trim().ifBlank { "Server ${i + 1}" })
            }

        var emitted = 0
        val emit: (ExtractorLink) -> Unit = { link ->
            emitted += 1
            callback(link)
        }

        for ((src, label) in candidates) {
            val type = streamType(src)
            if (type != null) {
                emit(newExtractorLink("AhwakTV", label, src, type) {
                    referer = data
                    quality = Qualities.Unknown.value
                    this.headers = this@AhwakTV.headers
                })
            } else {
                loadExtractor(src, data, subtitleCallback, emit)
            }
        }
        return emitted > 0
    }

    private fun searchInput(form: Element): Element? = form.select("input").firstOrNull {
        val type = it.attr("type").lowercase()
        val name = it.attr("name").lowercase()
        type != "hidden" && (type == "search" || name.contains("search") || name in setOf("q", "s", "story") ||
            name.contains("keyword") || it.attr("placeholder").contains("بحث") || it.attr("placeholder").contains("search", true))
    } ?: form.select("input[type=text][name]").firstOrNull {
        it.attr("name").lowercase() !in setOf("username", "user", "email", "login")
    }

    private fun relatedCategories(doc: Document): List<SearchResponse> {
        val heading = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull {
            it.text().contains("تصنيفات مشابهة") ||
                it.text().contains("similar categories", ignoreCase = true)
        } ?: return emptyList()

        val container = heading.parents().firstOrNull { parent ->
            val count = parent.select("a[href*='category.php'][href*='cat=']").size
            count in 1..15
        } ?: return emptyList()

        val seen = hashSetOf<String>()
        return container.select("a[href*='category.php'][href*='cat=']").mapNotNull { a ->
            val url = a.absUrl("href")
            val title = titleOf(a)
            if (!siteUrl(url) || title.length < 2 || !seen.add(url)) return@mapNotNull null
            val kind = categoryType(url, title) ?: TvType.TvSeries
            if (kind == TvType.Movie) {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = posterOf(a) }
            } else {
                newTvSeriesSearchResponse(title, url, kind) { posterUrl = posterOf(a) }
            }
        }
    }

    private fun categories(doc: Document): List<SearchResponse> {
        val seen = hashSetOf<String>()
        return doc.select("a[href]").mapNotNull { a ->
            val url = a.absUrl("href")
            if (!siteUrl(url) || !url.contains("category.php") ||
                !url.substringAfter('?', "").contains("cat=")) return@mapNotNull null
            val title = titleOf(a)
            if (title.length < 2 || !seen.add(url)) return@mapNotNull null
            val kind = categoryType(url, title) ?: TvType.TvSeries
            if (kind == TvType.Movie) {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = posterOf(a) }
            } else {
                newTvSeriesSearchResponse(title, url, kind) { posterUrl = posterOf(a) }
            }
        }
    }

    private fun media(doc: Document, fallbackType: TvType? = null): List<SearchResponse> {
        val seenUrls = hashSetOf<String>()
        val seenSeries = hashSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        for (a in doc.select("a[href]")) {
            val url = a.absUrl("href")
            if (!siteUrl(url)) continue
            val path = url.substringBefore('?').substringAfterLast('/').lowercase()
            if (path !in setOf("view-serie.php", "watch.php")) continue

            val title = titleOf(a)
            if (title.length < 2) continue

            if (path == "view-serie.php") {
                if (!seenUrls.add(url)) continue
                val seriesName = baseSeriesTitle(title).ifBlank { title }
                val key = normalizeTitle(seriesName)
                if (key.isBlank() || !seenSeries.add(key)) continue
                val kind = typeOf(title, fallbackType ?: TvType.TvSeries) ?: TvType.TvSeries
                results += newTvSeriesSearchResponse(seriesName, url, kind) {
                    posterUrl = posterOf(a)
                }
                continue
            }

            val kind = typeOf(title, fallbackType) ?: TvType.Movie
            if (kind == TvType.Movie) {
                if (!seenUrls.add(url)) continue
                results += newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = posterOf(a)
                }
            } else {
                val seriesName = baseSeriesTitle(title).ifBlank { title }
                val key = normalizeTitle(seriesName)
                if (key.isBlank() || !seenSeries.add(key)) continue
                results += newTvSeriesSearchResponse(seriesName, url, kind) {
                    posterUrl = posterOf(a)
                }
            }
        }

        return results
    }

    private fun episodes(
        doc: Document,
        poster: String?,
        seriesName: String?,
        currentUrl: String? = null,
        extraDocs: List<Document> = emptyList(),
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()
        val counts = hashMapOf<Int, Int>()
        val seriesKey = normalizeTitle(seriesName.orEmpty())

        for (sourceDoc in listOf(doc) + extraDocs) {
            val pageHasEpisodes = hasEpisodeSection(sourceDoc)
            for (a in sourceDoc.select("a[href*='watch.php']")) {
                val url = a.absUrl("href")
                if (!siteUrl(url)) continue

                val label = titleOf(a).ifBlank { a.text().trim() }
                val numberFromLabel = episodeNumber(label)
                val episodeOnly = label.matches(Regex("""^\s*[0-9٠-٩۰-۹]+\s*(?:حلقة|الحلقة)?\s*$"""))
                val inEpisodeSection = pageHasEpisodes && episodeOnly
                val sameSeries = seriesKey.isBlank() || normalizeTitle(label).contains(seriesKey)

                if (seriesName != null && !sameSeries && !inEpisodeSection) continue
                if (label.isBlank() && !episodeOnly) continue

                val context = a.parents().take(6).joinToString(" ") {
                    it.id() + " " + it.classNames().joinToString(" ") + " " +
                        it.attr("data-season") + " " +
                        (it.selectFirst("h2,h3,h4")?.text() ?: "")
                }
                val season = seasonNumber(context) ?: seasonNumber(label) ?: 1
                val nextIndex = (counts[season] ?: 0) + 1
                val number = numberFromLabel ?: nextIndex
                counts[season] = maxOf(nextIndex, number)
                val displayName = if (episodeOnly) "الحلقة $number" else label

                result.putIfAbsent(url, newEpisode(url) {
                    name = displayName
                    episode = number
                    this.season = season
                    posterUrl = poster
                })
            }
        }

        if (currentUrl != null && siteUrl(currentUrl) && !result.containsKey(currentUrl)) {
            val currentTitle = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            val number = episodeNumber(currentTitle) ?: 1
            result[currentUrl] = newEpisode(currentUrl) {
                name = currentTitle.ifBlank { "الحلقة $number" }
                episode = number
                season = seasonNumber(currentTitle) ?: 1
                posterUrl = poster
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private suspend fun categoryPages(url: String, firstDoc: Document): List<Document> {
        val pages = mutableListOf<Document>()
        val visited = hashSetOf(firstDoc.location().ifBlank { url })
        var current = firstDoc

        for (page in 2..20) {
            val next = pageUrl(current, page) ?: nextUrl(current) ?: break
            if (!siteUrl(next) || !visited.add(next)) break
            val nextDoc = try {
                app.get(next, headers = headers, referer = current.location().ifBlank { url }).document
            } catch (_: Exception) {
                break
            }
            pages += nextDoc
            current = nextDoc
            if (!hasNext(current, page)) break
        }
        return pages
    }

    private fun titleOf(a: Element): String = listOf(
        a.attr("title"),
        a.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
        a.selectFirst("img")?.attr("alt").orEmpty(),
        a.text(),
        a.parent()?.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
    ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun baseSeriesTitle(title: String): String {
        val withoutPrefix = title.trim().replace(
            Regex("^(?:مسلسل|انمي|أنمي|برنامج)\\s*", RegexOption.IGNORE_CASE),
            ""
        )
        val withoutEpisode = withoutPrefix.replace(
            Regex("\\s*(?:الحلقة|حلقة|episode|ep\\.?|الموسم|season)\\s*.*$", RegexOption.IGNORE_CASE),
            ""
        )
        return withoutEpisode.replace(
            Regex("\\s+(?:HD|FHD|FULL\\s*HD|مترجم|مدبلج|كامل)+$", RegexOption.IGNORE_CASE),
            ""
        ).trim(' ', '-', ':', '|')
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[\\s\\p{Punct}ـ]+"), "")

    private fun seasonNumber(value: String): Int? {
        val normalized = digits(value)
        Regex("(?:الموسم|season|\\bS\\s*)[^0-9]{0,8}(\\d+)", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val lower = value.lowercase()
        val ordinals = listOf(
            listOf("الأول", "الاول", "الأولى", "الاولى", "first", "one") to 1,
            listOf("الثاني", "الثانية", "second", "two") to 2,
            listOf("الثالث", "الثالثة", "third", "three") to 3,
            listOf("الرابع", "الرابعة", "fourth", "four") to 4,
            listOf("الخامس", "الخامسة", "fifth", "five") to 5,
            listOf("السادس", "السادسة", "sixth", "six") to 6,
            listOf("السابع", "السابعة", "seventh", "seven") to 7,
            listOf("الثامن", "الثامنة", "eighth", "eight") to 8,
            listOf("التاسع", "التاسعة", "ninth", "nine") to 9,
            listOf("العاشر", "العاشرة", "tenth", "ten") to 10,
        )
        return ordinals.firstOrNull { (words, _) ->
            words.any { lower.contains(it) }
        }?.second
    }

    private fun episodeNumber(value: String): Int? {
        val normalized = digits(value)
        val patterns = listOf(
            Regex("(?:الحلقة|حلقة|episode|ep\\.?)[^0-9]{0,8}(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s*(?:الحلقة|حلقة|episode|ep\\.?)", RegexOption.IGNORE_CASE),
        )
        patterns.firstNotNullOfOrNull { it.find(normalized)?.groupValues?.get(1)?.toIntOrNull() }?.let { return it }

        val lower = normalized.lowercase()
        val ordinals = listOf(
            listOf("الأولى", "الاولى", "الأول", "الاول", "first", "one") to 1,
            listOf("الثانية", "الثاني", "second", "two") to 2,
            listOf("الثالثة", "الثالث", "third", "three") to 3,
            listOf("الرابعة", "الرابع", "fourth", "four") to 4,
            listOf("الخامسة", "الخامس", "fifth", "five") to 5,
            listOf("السادسة", "السادس", "sixth", "six") to 6,
            listOf("السابعة", "السابع", "seventh", "seven") to 7,
            listOf("الثامنة", "الثامن", "eighth", "eight") to 8,
            listOf("التاسعة", "التاسع", "ninth", "nine") to 9,
            listOf("العاشرة", "العاشر", "tenth", "ten") to 10,
        )
        return ordinals.firstNotNullOfOrNull { (words, number) ->
            if (words.any { lower.contains(it) }) number else null
        }
    }

    private fun seriesTitleFromPage(doc: Document, fallback: String): String {
        val heading = doc.select("h2,h3,.series-title,.serie-title")
            .map { it.text().trim() }
            .firstOrNull {
                it.isNotBlank() &&
                    !it.contains("الحلقة", ignoreCase = true) &&
                    !it.contains("المواسم", ignoreCase = true)
            }
        return baseSeriesTitle(heading ?: fallback).ifBlank { baseSeriesTitle(fallback) }
    }

    private fun hasEpisodeSection(doc: Document): Boolean =
        doc.text().contains("المواسم والحلقات") && doc.select("a[href*='watch.php']").isNotEmpty()

    private fun isSeriesTitle(title: String): Boolean =
        typeOf(title, null) in setOf(TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    private fun posterOf(a: Element): String? {
        val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: return null
        return listOf("data-src", "data-original", "data-lazy-src", "src")
            .firstNotNullOfOrNull { img.absUrl(it).takeIf { u -> http(u) } }
    }

    private fun typeOf(title: String, fallback: TvType? = null): TvType? = when {
        title.contains("فيلم") || title.contains("أفلام") || title.contains("افلام") ||
            title.contains("مسرحية") -> TvType.Movie
        title.contains("أنمي") || title.contains("انمي") || title.contains("anime", true) -> TvType.Anime
        title.contains("كرتون") || title.contains("cartoon", true) -> TvType.Cartoon
        title.contains("مسلسل") || title.contains("برنامج") ||
            title.contains("الحلقة") || title.contains("episode", true) -> TvType.TvSeries
        else -> fallback
    }

    private fun categoryType(url: String, label: String = ""): TvType? {
        val slug = url.substringAfter("cat=", "").substringBefore('&').lowercase()
        return when {
            slug.startsWith("aflam") || slug.contains("movie") || slug.contains("film") -> TvType.Movie
            slug.contains("anime") -> TvType.Anime
            slug.contains("cartoon") -> TvType.Cartoon
            slug.contains("msr7") -> TvType.Movie
            slug.contains("tv-show") || slug.contains("series") || slug.contains("moslslat") -> TvType.TvSeries
            else -> typeOf(label, null)
        }
    }

    private fun digits(s: String) = buildString {
        s.forEach { c -> append(when (c) {
            in '٠'..'٩' -> '0' + (c - '٠')
            in '۰'..'۹' -> '0' + (c - '۰')
            else -> c
        }) }
    }

    private fun hasNext(doc: Document, page: Int): Boolean = nextUrl(doc) != null ||
        doc.select("a[href]").any { (Regex("[?&]page=(\\d+)").find(it.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0) > page }

    private fun pageUrl(doc: Document, page: Int): String? = doc.select(
        ".pagination a[href], .pages a[href], .page-numbers a[href], a[href*='page=']"
    ).firstNotNullOfOrNull { a ->
        val u = a.absUrl("href")
        if (Regex("[?&]page=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull() == page && siteUrl(u)) u else null
    }

    private fun nextUrl(doc: Document): String? {
        val labels = setOf("»", "›", ">", "التالي", "next", "next page")
        return doc.select("a[rel=next][href], .pagination a[href], .pages a[href], .page-numbers a[href]")
            .firstNotNullOfOrNull { a ->
                val label = a.text().trim().lowercase()
                val title = a.attr("title").trim().lowercase()
                val url = a.absUrl("href")
                if ((label in labels || title in labels || a.attr("aria-label").lowercase() in labels) && siteUrl(url)) url else null
            }
    }

    private fun queryUrl(url: String, values: Map<String, String>): String = url +
        (if (url.contains('?')) "&" else "?") + values.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8") }=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    private fun isCategoryIndex(url: String) = url.substringBefore('?').substringAfterLast('/') == "category.php" &&
        !url.substringAfter('?', "").contains("cat=")
    private fun siteUrl(url: String) = url.startsWith("$mainUrl/") || url == mainUrl
    private fun http(url: String) = url.startsWith("https://", true) || url.startsWith("http://", true)
    private fun absolute(el: Element, key: String): String {
        val u = el.absUrl(key)
        if (u.isNotBlank()) return u
        val raw = el.attr(key).trim()
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> mainUrl + raw
            else -> raw
        }
    }

    private fun streamType(url: String): ExtractorLinkType? = when (url.substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.')) {
        "m3u8" -> ExtractorLinkType.M3U8
        "mpd" -> ExtractorLinkType.DASH
        "mp4", "webm" -> ExtractorLinkType.VIDEO
        else -> null
    }
}