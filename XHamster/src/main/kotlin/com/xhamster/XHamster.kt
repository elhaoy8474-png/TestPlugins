package com.xhamster

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class XHamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "XHamster"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 12; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun requestHeaders(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to referer
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/newest" to "الأحدث",
        "$mainUrl/best/weekly" to "الأكثر مشاهدة"
    )

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() && it != "null" && !it.startsWith("data:") }

    private fun resolveUrl(raw: String): String {
        val value = raw.trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
        return try {
            URI(mainUrl).resolve(value).toString()
        } catch (_: Exception) {
            value
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isBlank() || !href.contains("/videos/")) return null

        val image = selectFirst("img")
        val title = firstNonBlank(
            attr("aria-label"),
            image?.attr("alt").orEmpty(),
            attr("title"),
            selectFirst(".video-thumb-info__name")?.text().orEmpty(),
            text()
        ) ?: return null

        val poster = image?.let {
            firstNonBlank(
                it.attr("data-src"),
                it.attr("data-original"),
                it.attr("src"),
                it.attr("data-lazy-src")
            )
        }?.let(::resolveUrl)

        return newMovieSearchResponse(title.trim(), resolveUrl(href), TvType.Movie) {
            posterUrl = poster
        }
    }

    private fun extractResults(html: String): List<SearchResponse> {
        val document = Jsoup.parse(html, mainUrl)
        return document.select(
            "a.video-thumb__image-container[href*='/videos/'], " +
                "a[data-role=thumb-link][href*='/videos/']"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) request.data else "$base/$page"
        val html = try {
            app.get(pageUrl, headers = requestHeaders()).text
        } catch (_: Exception) {
            ""
        }
        return newHomePageResponse(request.name, extractResults(html))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encoded"
        val html = try {
            app.get(searchUrl, headers = requestHeaders()).text
        } catch (_: Exception) {
            return emptyList()
        }
        return extractResults(html)
    }

    private fun JSONObject.stringValue(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    /*
     * XHamster places the public page state in window.initials. Parse the JSON
     * object with a string-aware brace scanner; do not evaluate page JS.
     */
    private fun extractInitials(html: String): JSONObject? {
        val marker = html.indexOf("window.initials")
        if (marker < 0) return null

        val assignment = html.indexOf('=', marker)
        if (assignment < 0) return null

        val start = html.indexOf('{', assignment + 1)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until html.length) {
            val character = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JSONObject(html.substring(start, index + 1))
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun videoModel(initials: JSONObject?): JSONObject? =
        initials?.optJSONObject("videoModel")

    private fun extractTags(initials: JSONObject?): List<String> {
        val tags = initials
            ?.optJSONObject("videoTagsComponent")
            ?.optJSONArray("tags") ?: return emptyList()

        val result = ArrayList<String>()
        for (index in 0 until tags.length()) {
            val item = tags.opt(index)
            val value = when (item) {
                is JSONObject -> firstNonBlank(
                    item.stringValue("name").orEmpty(),
                    item.stringValue("title").orEmpty(),
                    item.stringValue("label").orEmpty()
                )
                is String -> item
                else -> null
            }
            if (!value.isNullOrBlank()) result.add(value.trim())
        }
        return result.distinct()
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = resolveUrl(url)
        val html = try {
            app.get(pageUrl, headers = requestHeaders(pageUrl)).text
        } catch (_: Exception) {
            return null
        }

        val document = Jsoup.parse(html, pageUrl)
        val initials = extractInitials(html)
        val video = videoModel(initials)

        val title = firstNonBlank(
            video?.stringValue("title").orEmpty(),
            document.selectFirst("h1")?.text().orEmpty(),
            document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(),
            document.title().substringBefore("|").trim()
        ) ?: return null

        val poster = firstNonBlank(
            video?.stringValue("thumbURL").orEmpty(),
            document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
            document.selectFirst("meta[name=twitter:image]")?.attr("content").orEmpty()
        )?.let(::resolveUrl)

        val plot = firstNonBlank(
            video?.stringValue("description").orEmpty(),
            document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        )
        val tags = extractTags(initials)

        return newMovieLoadResponse(title.trim(), pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    private data class StreamCandidate(
        val url: String,
        val label: String,
        val quality: Int,
        val type: ExtractorLinkType
    )

    private fun qualityValue(label: String, url: String): Int {
        val match = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find("$label $url")
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun decodeHex(hex: String): String? {
        if (hex.length < 12 || hex.length % 2 != 0 || !hex.matches(Regex("[0-9a-fA-F]+"))) {
            return null
        }

        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            val start = index * 2
            bytes[index] = hex.substring(start, start + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        if (bytes.size <= 5) return null

        val algorithm = bytes[0].toInt() and 0xff
        var state = (bytes[1].toInt() and 0xff) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            ((bytes[3].toInt() and 0xff) shl 16) or
            ((bytes[4].toInt() and 0xff) shl 24)

        val output = ByteArray(bytes.size - 5)
        for (index in 5 until bytes.size) {
            val streamValue = when (algorithm) {
                1 -> {
                    state = state * 1664525 + 1013904223
                    state
                }
                2 -> {
                    var next = state
                    next = next xor (next shl 13)
                    next = next xor (next ushr 17)
                    next = next xor (next shl 5)
                    state = next
                    next
                }
                3 -> {
                    state += 0x9e3779b9.toInt()
                    var next = state
                    next = next xor (next ushr 16)
                    next *= 0x85ebca77.toInt()
                    next = next xor (next ushr 13)
                    next *= 0xc2b2ae3d.toInt()
                    next xor (next ushr 16)
                }
                4 -> {
                    state += 0x6d2b79f5
                    var next = (state shl 7) or (state ushr 25)
                    next += 0x9e3779b9.toInt()
                    next = next xor (next ushr 11)
                    next * 0x27d4eb2d
                }
                5 -> {
                    var next = state
                    next = next xor (next shl 7)
                    next = next xor (next ushr 9)
                    next = next xor (next shl 8)
                    state = next + 0xa5a5a5a5.toInt()
                    state
                }
                6 -> {
                    state = state * 0x2c9277b5 + 0xac564b05.toInt()
                    val mixed = state xor (state ushr 18)
                    mixed ushr ((state ushr 27) and 31)
                }
                7 -> {
                    state += 0x9e3779b9.toInt()
                    var next = state xor (state shl 5)
                    next *= 0x7feb352d
                    next = next xor (next ushr 15)
                    next * 0x846ca68b.toInt()
                }
                else -> return null
            }

            val key = streamValue and 0xff
            output[index - 5] = (((bytes[index].toInt() and 0xff) xor key) and 0xff).toByte()
        }

        return String(output, StandardCharsets.ISO_8859_1)
    }

    /*
     * A source may be a complete URL, an encrypted hex payload, or a URL whose
     * first path component is encrypted. Only normal HTTPS video CDN URLs are
     * emitted; media segments and non-video endpoints are deliberately ignored.
     */
    private fun decipherStreamUrl(raw: String): String? {
        var candidate = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim('"', '\'')

        if (candidate.isBlank()) return null

        if (candidate.matches(Regex("[0-9a-fA-F]{12,}"))) {
            candidate = decodeHex(candidate) ?: return null
        } else {
            val uri = try {
                URI(candidate)
            } catch (_: Exception) {
                return null
            }

            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                val pathMatch = Regex("^/([0-9a-fA-F]{12,})([/,].*)?$")
                    .find(uri.rawPath.orEmpty())
                if (pathMatch != null) {
                    val encryptedPart = pathMatch.groupValues[1]
                    val decodedPart = decodeHex(encryptedPart) ?: return null
                    val marker = "/$encryptedPart"
                    val pathStart = candidate.indexOf(marker, candidate.indexOf("://") + 3)
                    if (pathStart < 0) return null
                    candidate = candidate.replaceRange(
                        pathStart,
                        pathStart + marker.length,
                        "/$decodedPart"
                    )
                }
            }
        }

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme != "https" || host.isBlank()) return null

        val allowedHosts = listOf(
            "xhcdn.com",
            "xhpingcdn.com",
            "mulewosizeseha.com",
            "ahcdn.com"
        )
        if (allowedHosts.none { host == it || host.endsWith(".$it") }) return null

        val path = uri.rawPath.orEmpty().lowercase()
        if (path.endsWith(".m4s") || path.endsWith(".ts") ||
            path.contains("/seg-") || path.contains("/init-")
        ) return null

        val isPlaylist = path.endsWith(".m3u8") || path.contains("media=hls")
        val isVideoFile = path.endsWith(".mp4")
        if (!isPlaylist && !isVideoFile) return null

        return candidate
    }

    private fun addStream(
        destination: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        raw: String?,
        label: String,
        qualityLabel: String = "",
        forceHls: Boolean = false
    ) {
        if (raw.isNullOrBlank() || raw == "null") return
        val resolved = decipherStreamUrl(raw) ?: return
        if (!seen.add(resolved)) return

        val uri = try {
            URI(resolved)
        } catch (_: Exception) {
            return
        }
        val path = uri.rawPath.orEmpty().lowercase()
        val type = if (forceHls || path.endsWith(".m3u8") || path.contains("media=hls")) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        destination.add(
            StreamCandidate(
                url = resolved,
                label = label,
                quality = qualityValue(qualityLabel, resolved),
                type = type
            )
        )
    }

    private fun collectHlsSources(
        hls: JSONObject,
        destination: MutableList<StreamCandidate>,
        seen: MutableSet<String>
    ) {
        val codecKeys = hls.keys().asSequence().toList()
        val nestedSources = codecKeys.filter { hls.opt(it) is JSONObject }

        if (nestedSources.isNotEmpty()) {
            for (codec in nestedSources) {
                val node = hls.optJSONObject(codec) ?: continue
                val label = "HLS " + codec.uppercase()
                addStream(destination, seen, node.stringValue("url"), label, forceHls = true)
                addStream(destination, seen, node.stringValue("fallback"), "$label alternate", forceHls = true)
            }
        } else {
            addStream(destination, seen, hls.stringValue("url"), "HLS", forceHls = true)
            addStream(destination, seen, hls.stringValue("fallback"), "HLS alternate", forceHls = true)
        }
    }

    private fun collectStandardSources(
        standard: JSONObject,
        destination: MutableList<StreamCandidate>,
        seen: MutableSet<String>
    ) {
        val codecs = standard.keys()
        while (codecs.hasNext()) {
            val codec = codecs.next()
            val formats = standard.optJSONArray(codec) ?: continue

            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val quality = firstNonBlank(
                    format.stringValue("quality").orEmpty(),
                    format.stringValue("label").orEmpty()
                ).orEmpty()
                val label = listOf(codec.uppercase(), quality)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "Video" }
                val typeHint = format.stringValue("type").orEmpty()
                val primaryUrl = format.stringValue("url").orEmpty()
                val hls = typeHint.contains("hls", true) ||
                    primaryUrl.contains(".m3u8", true) ||
                    primaryUrl.contains("media=hls", true)

                addStream(
                    destination,
                    seen,
                    format.stringValue("url"),
                    label,
                    quality,
                    forceHls = hls
                )
                addStream(
                    destination,
                    seen,
                    format.stringValue("fallback"),
                    "$label alternate",
                    quality,
                    forceHls = hls
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = resolveUrl(data)
        val html = try {
            app.get(pageUrl, headers = requestHeaders(pageUrl)).text
        } catch (_: Exception) {
            return false
        }

        if (Regex("""id=["']videoClosed["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            return false
        }

        val initials = extractInitials(html) ?: return false
        val entity = initials.optJSONObject("videoEntity")
        if (entity?.optBoolean("isGeoBlocked", false) == true) return false

        val settings = initials.optJSONObject("xplayerSettings") ?: return false
        val sources = settings.optJSONObject("sources") ?: return false

        val candidates = ArrayList<StreamCandidate>()
        val seen = HashSet<String>()

        sources.optJSONObject("hls")?.let { hls ->
            collectHlsSources(hls, candidates, seen)
        }
        sources.optJSONObject("standard")?.let { standard ->
            collectStandardSources(standard, candidates, seen)
        }

        var emitted = false
        for (stream in candidates) {
            try {
                callback(
                    newExtractorLink(
                        source = name,
                        name = stream.label,
                        url = stream.url,
                        type = stream.type
                    ) {
                        referer = pageUrl
                        quality = stream.quality
                    }
                )
                emitted = true
            } catch (_: Exception) {
                // Keep other valid qualities available if one candidate is malformed.
            }
        }
        return emitted
    }
}
