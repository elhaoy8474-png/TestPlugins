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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun resolveUrl(raw: String): String {
        val value = raw.trim().replace("\\/", "/").replace("&amp;", "&")
        return try { URI(mainUrl).resolve(value).toString() } catch (_: Exception) { value }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "أفلام - الرئيسية",
        "$mainUrl/category.php?cat=asian-movies" to "أفلام آسيوية",
        "https://a.qfilm.cam/seriestv" to "المسلسلات - الكل",
        "https://a.qfilm.cam/category/مسلسلات-عربية" to "مسلسلات عربية",
        "https://a.qfilm.cam/category/مسلسلات-تركية" to "مسلسلات تركية",
        "https://a.qfilm.cam/category/مسلسلات-اجنبية" to "مسلسلات أجنبية",
        "https://a.qfilm.cam/category/مسلسلات-اسيوية" to "مسلسلات آسيوية",
        "https://a.qfilm.cam/category/مسلسلات-اهواك" to "مسلسلات اهواك",
        "https://a.qfilm.cam/category/مسلسلات-مصرية" to "مسلسلات مصرية",
        "https://a.qfilm.cam/category/مسلسلات-هندية" to "مسلسلات هندية"
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
        return if (title.contains("مسلسل", true) && !title.contains("الحلقة", true)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        } else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
    }

    private fun extractResults(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        return doc.select("a[href*='play.php?vid='], a[href*='watch.php?vid='], .pm-video-thumb, .video-item, .movie-item, .thumbnail")
            .mapNotNull { it.toResult() }.distinctBy { it.url }
    }

    private suspend fun seriesResults(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, "https://a.qfilm.cam")
        // QFilm's category pages are episode feeds, not series-card feeds.
        // Resolve one episode per show and use its explicit "series page" link.
        val candidates = linkedMapOf<String, Pair<String, String>>()
        doc.select("a[href*='qdrama-']").forEach { a ->
            val href = a.absUrl("href").ifBlank {
                try { URI("https://a.qfilm.cam").resolve(a.attr("href")).toString() }
                catch (_: Exception) { a.attr("href") }
            }
            val label = a.attr("title").ifBlank { a.text() }.trim()
            if (href.isBlank() || label.isBlank()) return@forEach
            val normalized = label
                .replace(Regex("(?i)^مسلسل\\s*"), "")
                .replace(Regex("(?i)\\s*(?:الموسم\\s*\\d+\\s*)?الحلقة.*$"), "")
                .replace(Regex("(?i)\\s*(?:season\\s*\\d+\\s*)?episode.*$"), "")
                .replace(Regex("(?i)\\s*ep\\.?\\s*\\d+.*$"), "")
                .trim()
            if (normalized.isBlank()) return@forEach
            candidates.putIfAbsent(normalized.lowercase(), normalized to href)
        }

        val results = linkedMapOf<String, SearchResponse>()
        for ((fallbackTitle, episodeUrl) in candidates.values.take(20)) {
            try {
                val episodeHtml = app.get(episodeUrl, headers = mapOf("Referer" to "https://a.qfilm.cam/")).text
                val episodeDoc = Jsoup.parse(episodeHtml, episodeUrl)
                val seriesAnchor = episodeDoc.selectFirst("a[href*='/series/']") ?: continue
                val seriesUrl = seriesAnchor.absUrl("href").ifBlank { continue }
                val title = seriesAnchor.attr("title").ifBlank { seriesAnchor.text() }
                    .ifBlank { fallbackTitle }.trim()
                if (title.isBlank()) continue
                val poster = episodeDoc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.startsWith("http") }
                results.putIfAbsent(seriesUrl, newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                    posterUrl = poster
                })
            } catch (_: Exception) {
                // A single unavailable episode must not break the category page.
            }
        }
        return results.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else {
            val separator = if (request.data.contains("?")) "&" else "?"
            request.data + separator + "page=$page"
        }
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        return if (request.data.contains("a.qfilm.cam")) {
            newHomePageResponse(request.name, seriesResults(html))
        } else {
            newHomePageResponse(request.name, extractResults(html))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = app.get("$mainUrl/search.php?keywords=$encoded", headers = mapOf("Referer" to "$mainUrl/")).text
        return extractResults(html)
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("a.qfilm.cam")) {
            val episodePage = app.get(url, headers = mapOf("Referer" to "https://a.qfilm.cam/")).text
            val episodeDoc = Jsoup.parse(episodePage, url)
            val seriesUrl = if (url.contains("/series/")) url else episodeDoc.selectFirst("a[href*='/series/']")?.absUrl("href").orEmpty()
            if (seriesUrl.isNotBlank()) {
                val seriesHtml = if (seriesUrl == url) episodePage else app.get(seriesUrl, headers = mapOf("Referer" to url)).text
                val doc = Jsoup.parse(seriesHtml, seriesUrl)
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
                val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                val seen = hashSetOf<String>()
                val episodes = doc.select("a[href]").mapNotNull { a ->
                    val href = a.attr("href")
                    if (!href.contains("qdrama-")) return@mapNotNull null
                    val episodeUrl = a.absUrl("href").ifBlank { try { URI("https://a.qfilm.cam").resolve(href).toString() } catch (_: Exception) { href } }
                    val label = a.attr("title").ifBlank { a.text() }.trim()
                    if (label.isBlank() || !seen.add(episodeUrl)) return@mapNotNull null
                    episodeUrl to label
                }.mapIndexed { index, item ->
                    val number = episodeNumber(item.second, item.first) ?: (index + 1)
                    val seasonMatch = Regex("(?i)(?:الموسم|season)\\s*(\\d+)").find(item.second)
                    newEpisode(data = item.first) {
                        name = item.second
                        episode = number
                        season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        posterUrl = poster
                    }
                }
                    .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })
                return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    this.plot = plot
                }
            }
        }
        val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1, .pm-video-title")?.text()?.trim()
            ?: doc.title().substringBefore(" HD").trim()
        val poster: String? = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { resolveUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val episodes = mutableListOf<Episode>()
        val seen = hashSetOf<String>()
        doc.select("a[href*='watch.php?vid='], a[href*='play.php?vid=']").forEach { element ->
            val episodeUrl = resolveUrl(element.attr("href"))
            val episodeId = Regex("[?&]vid=([^&#]+)", RegexOption.IGNORE_CASE)
                .find(episodeUrl)?.groupValues?.get(1) ?: return@forEach
            if (!seen.add(episodeId)) return@forEach
            val label = element.attr("title").ifBlank { element.text() }.trim()
            if (!label.contains("حلقة", true) && !label.contains("episode", true)) return@forEach
            val number = episodeNumber(label, episodeUrl) ?: (episodes.size + 1)
            episodes.add(newEpisode(data = episodeUrl) {
                name = label.ifBlank { "الحلقة $number" }
                episode = number
                season = 1
                posterUrl = poster
            })
        }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
        }
    }

    // Same packed-JavaScript fallback used by the working AhwakTV provider.
    private fun unpackPackedJs(source: String): String? {
        val eval = Regex(
            """eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(source) ?: return null
        val args = Regex(
            """['"](.+?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.+?)['"]\.split\s*\(['"]\|['"]\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(eval.groupValues[1]) ?: return null
        val packed = args.groupValues[1]
        val radix = args.groupValues[2].toIntOrNull() ?: return null
        val count = args.groupValues[3].toIntOrNull() ?: return null
        val keys = args.groupValues[4].split('|')
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun base(value: Int): String {
            if (value == 0) return "0"
            var n = value
            val out = StringBuilder()
            while (n > 0) {
                out.insert(0, chars[n % radix])
                n /= radix
            }
            return out.toString()
        }
        val replacements = HashMap<String, String>()
        for (i in 0 until count) keys.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
            replacements[base(i)] = it
        }
        return Regex("""\b\w+\b""").replace(packed) { match ->
            replacements[match.value] ?: match.value
        }
    }

    private fun jsonString(json: String, key: String): String? =
        Regex(""""${key}"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    private fun episodeNumber(label: String, url: String = ""): Int? {
        val text = "$label $url".map {
            if (it in '\u0660'..'\u0669') ('0' + (it - '\u0660')) else it
        }.joinToString("")
        val patterns = listOf(
            Regex("(?i)الحلقة\\s*#?\\s*(\\d+)"),
            Regex("(?i)episode\\s*#?\\s*(\\d+)"),
            Regex("(?i)ep\\.?\\s*#?\\s*(\\d+)")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }
    private fun decodeUrlBase64(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    // Native implementation of CloudStream's ByseSX extractor for QFilm's Byse player.
    private suspend fun extractByse(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val uri = URI(embedUrl)
            val base = "${uri.scheme}://${uri.host}"
            val code = uri.path.trimEnd('/').substringAfterLast('/')
            if (code.isBlank()) return false
            val details = app.get("$base/api/videos/$code/embed/details", headers = mapOf("Referer" to "$base/")).text
            val frameUrl = jsonString(details, "embed_frame_url") ?: return false
            val frameUri = URI(frameUrl)
            val frameBase = "${frameUri.scheme}://${frameUri.host}"
            val frameCode = frameUri.path.trimEnd('/').substringAfterLast('/')
            if (frameCode.isBlank()) return false
            val playback = app.get("$frameBase/api/videos/$frameCode/embed/playback", headers = mapOf(
                "accept" to "*/*", "referer" to frameUrl, "x-embed-parent" to embedUrl
            )).text
            val iv = decodeUrlBase64(jsonString(playback, "iv") ?: return false)
            val payload = decodeUrlBase64(jsonString(playback, "payload") ?: return false)
            val parts = Regex(""""key_parts"\s*:\s*\[\s*"([^"]+)"\s*,\s*"([^"]+)"""").find(playback) ?: return false
            val key = decodeUrlBase64(parts.groupValues[1]) + decodeUrlBase64(parts.groupValues[2])
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val clear = cipher.doFinal(payload).toString(Charsets.UTF_8)
            val stream = jsonString(clear, "url") ?: return false
            if (!stream.startsWith("http")) return false
            // Use CloudStream HLS playlist expansion and preserve the Byse Referer.
            M3u8Helper.generateM3u8(
                "QFilm - Byse",
                stream,
                base,
                headers = mapOf("Referer" to "$base/")
            ).forEach(callback)
            true
        } catch (_: Exception) { false }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("a.qfilm.cam")) {
            val cleanUrl = data.substringBefore("?").trimEnd('/')
            val slug = cleanUrl.substringAfterLast('/')
            val playerUrl = if (cleanUrl.contains("/watch/")) cleanUrl + "/servers" else "https://a.qfilm.cam/watch/$slug/servers"
            return try {
                val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to data, "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")).text
                val playerDoc = Jsoup.parse(playerHtml, playerUrl)
                val servers = linkedSetOf<String>()
                playerDoc.select("iframe[src], iframe[data-src], a[href]").forEach { element ->
                    val candidate = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
                    if (candidate.startsWith("http") && !candidate.contains("a.qfilm.cam")) servers.add(candidate)
                }
                // Try the CloudStream extractor first, then inspect the host page
                // for native video/source tags or packed-JS file URLs (AhwakTV pattern).
                var found = false
                for (server in servers) {
                    try {
                        if (server.contains("bysetayico.com", true) && extractByse(server, callback)) {
                            found = true
                            continue
                        }
                        if (loadExtractor(server, playerUrl, subtitleCallback, callback)) {
                            found = true
                            continue
                        }
                        val hostHtml = app.get(server, headers = mapOf(
                            "Referer" to playerUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                        )).text
                        val unpacked = unpackPackedJs(hostHtml) ?: hostHtml
                        val hostDoc = Jsoup.parse(unpacked, server)
                        val directFiles = linkedSetOf<String>()
                        hostDoc.select("video[src], video source[src], source[src]").forEach { node ->
                            node.absUrl("src").takeIf { it.startsWith("http") }?.let(directFiles::add)
                        }
                        Regex("""['"]?file['"]?\s*[:=]\s*['"](https?[^'"]+)['"]""", RegexOption.IGNORE_CASE)
                            .findAll(unpacked).forEach { directFiles.add(it.groupValues[1].replace("\\/", "/")) }
                        directFiles.forEach { file ->
                            val type = if (file.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback(newExtractorLink(name, "QFilm - ${server.substringAfter("://").substringBefore("/")}", file, type) {
                                referer = server
                                quality = Qualities.Unknown.value
                            })
                            found = true
                        }
                    } catch (_: Exception) { }
                }
                found
            } catch (_: Exception) { false }
        }
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
