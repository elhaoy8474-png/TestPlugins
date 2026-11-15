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
        val items = if (isCategoryIndex(request.data)) categories(doc) else media(doc)
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
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() } ?: doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: url.substringBefore('?').substringAfterLast('/').substringBefore('.').ifBlank { "AhwakTV" }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { image -> if (image.startsWith("http")) image else mainUrl + "/" + image.trimStart('/') }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val path = url.substringBefore('?').substringAfterLast('/')
        val episodes = episodes(doc, poster)
        val category = path == "category.php" && url.substringAfter('?', "").contains("cat=")
        if (path == "view-serie.php" || category) {
            return newTvSeriesLoadResponse(title, url, if (category) TvType.TvSeries else typeOf(title), episodes) {
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
        if (!data.startsWith("http://") && !data.startsWith("https://")) return false
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

        var found = false
        for ((src, label) in candidates) {
            val type = streamType(src)
            if (type != null) {
                callback(newExtractorLink("AhwakTV", label, src, type) {
                    referer = data
                    quality = Qualities.Unknown.value
                    this.headers = this@AhwakTV.headers
                })
                found = true
            } else {
                found = loadExtractor(src, data, subtitleCallback, callback) || found
            }
        }
        return found
    }

    private fun searchInput(form: Element): Element? = form.select("input").firstOrNull {
        val type = it.attr("type").lowercase()
        val name = it.attr("name").lowercase()
        type != "hidden" && (type == "search" || name.contains("search") || name in setOf("q", "s", "story") ||
            name.contains("keyword") || it.attr("placeholder").contains("بحث") || it.attr("placeholder").contains("search", true))
    } ?: form.select("input[type=text][name]").firstOrNull {
        it.attr("name").lowercase() !in setOf("username", "user", "email", "login")
    }

    private fun categories(doc: Document): List<SearchResponse> {
        val seen = hashSetOf<String>()
        return doc.select("a[href]").mapNotNull { a ->
            val url = a.absUrl("href")
            if (!siteUrl(url) || !url.contains("category.php") || !url.substringAfter('?', "").contains("cat=")) return@mapNotNull null
            val title = titleOf(a)
            if (title.length < 2 || !seen.add(url)) return@mapNotNull null
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = posterOf(a) }
        }
    }

    private fun media(doc: Document): List<SearchResponse> {
        val seen = hashSetOf<String>()
        return doc.select("a[href]").mapNotNull { a ->
            val url = a.absUrl("href")
            if (!siteUrl(url)) return@mapNotNull null
            val path = url.substringBefore('?').substringAfterLast('/').lowercase()
            if (path !in setOf("view-serie.php", "watch.php")) return@mapNotNull null
            val title = titleOf(a)
            if (title.length < 2 || !seen.add(url)) return@mapNotNull null
            when (path) {
                "view-serie.php" -> newTvSeriesSearchResponse(title, url, typeOf(title)) { posterUrl = posterOf(a) }
                "watch.php" -> newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = posterOf(a) }
                else -> null
            }
        }
    }

    private fun episodes(doc: Document, poster: String?): List<Episode> {
        val seen = hashSetOf<String>()
        val counts = hashMapOf<Int, Int>()
        return doc.select("a[href*='watch.php']").mapNotNull { a ->
            val url = a.absUrl("href")
            if (!siteUrl(url)) return@mapNotNull null
            val label = titleOf(a)
            if (label.isBlank() || !seen.add(url)) return@mapNotNull null
            val context = a.parents().take(4).joinToString(" ") {
                it.id() + " " + it.classNames().joinToString(" ") + " " + it.attr("data-season") + " " +
                    (it.selectFirst("h2,h3,h4")?.text() ?: "")
            }
            val season = Regex("(?:الموسم|season|s)[^0-9]{0,6}(\\d+)", RegexOption.IGNORE_CASE)
                .find(digits(context))?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val index = (counts[season] ?: 0) + 1
            val number = Regex("(?:الحلقة|حلقة|episode|ep\\.?)[^0-9]{0,8}(\\d+)", RegexOption.IGNORE_CASE)
                .find(digits(label))?.groupValues?.get(1)?.toIntOrNull() ?: index
            counts[season] = maxOf(index, number)
            newEpisode(url = url, initializer = {
                name = label.ifBlank { "الحلقة $number" }
                episode = number
                this.season = season
                posterUrl = poster
            })
        }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    private fun titleOf(a: Element): String = listOf(
        a.attr("title"), a.text(), a.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
        a.selectFirst("img")?.attr("alt").orEmpty(),
        a.parent()?.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
    ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun posterOf(a: Element): String? {
        val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: return null
        return listOf("data-src", "data-original", "data-lazy-src", "src")
            .firstNotNullOfOrNull { img.absUrl(it).takeIf { u -> http(u) } }
    }

    private fun typeOf(title: String): TvType = when {
        title.contains("أنمي") || title.contains("انمي") || title.contains("anime", true) -> TvType.Anime
        title.contains("كرتون") || title.contains("cartoon", true) -> TvType.Cartoon
        title.contains("فيلم") || title.contains("أفلام") || title.contains("افلام") -> TvType.Movie
        else -> TvType.TvSeries
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