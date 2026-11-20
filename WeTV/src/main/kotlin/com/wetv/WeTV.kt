package com.wetv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WeTV : MainAPI() {
    override var mainUrl = "https://wetv.vip"
    override var name = "WeTV"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val apiUrl = "https://play.wetv.vip/getvinfo"
    private val appVersion = "3.5.57"
    private val platform = "4830201"
    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val webHeaders = mapOf(
        "User-Agent" to desktopUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/ar/"
    )
    private val apiHeaders = mapOf(
        "User-Agent" to desktopUa,
        "Accept" to "*/*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/ar/"
    )
    private val random = SecureRandom()
    override val mainPage = mainPageOf("$mainUrl/ar" to "الرئيسية")

    private data class EpisodePageItem(val id: String, val url: String, val number: Int, val isPaid: Boolean)
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun absUrl(value: String): String {
        val raw = value.trim().replace("&amp;", "&")
        return when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> mainUrl + raw
            else -> "$mainUrl/$raw"
        }
    }

    private fun seriesId(url: String): String? =
        Regex("/play/([A-Za-z0-9_-]+)(?:/|$)").find(url)?.groupValues?.get(1)

    private fun episodeIds(url: String): Pair<String, String>? {
        val match = Regex("/play/([A-Za-z0-9_-]+)/([A-Za-z0-9_-]+)").find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2].substringBefore('-')
    }

    private fun cardTitle(anchor: Element): String {
        val image = anchor.selectFirst("img")
        val title = image?.attr("alt")?.trim().orEmpty()
            .ifBlank { anchor.attr("title").trim() }
            .ifBlank { anchor.text().trim() }
            .ifBlank { anchor.parent()?.text()?.trim().orEmpty() }
        return title.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun cardPoster(anchor: Element): String? {
        val image = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img") ?: return null
        val url = image.attr("data-src").ifBlank {
            image.attr("data-original").ifBlank { image.attr("src") }
        }
        return url.takeIf { it.isNotBlank() }?.let(::absUrl)
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val document = Jsoup.parse(html, mainUrl)
        val output = linkedMapOf<String, SearchResponse>()
        document.select("a[href*='/ar/play/']").forEach { anchor ->
            val href = anchor.attr("href").substringBefore('?').substringBefore('#')
            val parts = href.trim('/').split('/')
            if (parts.size != 3 || parts[0] != "ar" || parts[1] != "play") return@forEach
            val id = parts[2].substringBefore('-')
            if (!id.matches(Regex("[A-Za-z0-9_]{6,32}")) || output.containsKey(id)) return@forEach
            val title = cardTitle(anchor)
            if (title.isBlank() || title.length > 180) return@forEach
            val image = cardPoster(anchor)
            output[id] = newTvSeriesSearchResponse(title, "$mainUrl/ar/play/$id", TvType.TvSeries) {
                posterUrl = image
            }
        }
        return output.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val html = app.get(request.data, headers = webHeaders).text
        return newHomePageResponse(request.name, parseCards(html))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val html = app.get("$mainUrl/ar/search?q=${encode(query)}", headers = webHeaders).text
        return parseCards(html)
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s*(?:观看在线| مشاهدة على الانترنت| مشاهدة كاملة مترجم| - WeTV)\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun isPaidMarker(value: String): Boolean =
        Regex("(?:دفع|VIP|أعضاء|عضوية|مدفوع|members? only|paid|subscribe|premium)", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)

    private fun parseEpisodeItems(document: org.jsoup.nodes.Document, cid: String): List<EpisodePageItem> {
        val found = linkedMapOf<String, EpisodePageItem>()
        document.select("a.play-video__link[href]").forEach { anchor ->
            val ids = episodeIds(absUrl(anchor.attr("href"))) ?: return@forEach
            if (ids.first != cid) return@forEach
            val numberText = anchor.attr("title").ifBlank { anchor.text() }.trim()
            val number = Regex("\\d+").find(numberText)?.value?.toIntOrNull() ?: return@forEach
            val paid = isPaidMarker(anchor.parent()?.text()?.trim().orEmpty())
            found[ids.second] = EpisodePageItem(ids.second, "$mainUrl/ar/play/$cid/${ids.second}", number, paid)
        }
        return found.values.sortedBy { it.number }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cid = seriesId(url) ?: return null
        val detailUrl = "$mainUrl/ar/play/$cid"
        val html = try { app.get(detailUrl, headers = webHeaders).text } catch (_: Exception) { return null }
        val document = Jsoup.parse(html, detailUrl)
        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { document.selectFirst("h1")?.text().orEmpty() }
            .orEmpty().ifBlank { document.title() }
        val title = cleanTitle(rawTitle).ifBlank { "WeTV - $cid" }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
        val plot = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        // Member-only/VIP entries are omitted from the visible episode list.
        val episodes = parseEpisodeItems(document, cid).filterNot { it.isPaid }.map { item ->
            newEpisode(item.url) {
                name = "الحلقة ${item.number}"
                episode = item.number
                season = 1
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private fun randomId(length: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun createCKey(videoId: String, episodeUrl: String, guid: String): String {
        val payload = "$videoId|${System.currentTimeMillis() / 1000}|mg3c3b04ba|$appVersion|$guid|$platform|${episodeUrl.take(48)}|${desktopUa.lowercase().take(48)}||Mozilla|Netscape|Windows x86_64|00|"
        val message = "|${payload.sumOf { it.code }}|$payload".toByteArray(StandardCharsets.UTF_8)
        val paddedLength = ((message.size + 15) / 16) * 16
        val padded = message.copyOf(paddedLength)
        for (index in message.size until paddedLength) padded[index] = 0x20
        val key = byteArrayOf(
            0x4f, 0x6b, 0xda.toByte(), 0xa3.toByte(), 0x9e.toByte(), 0x2f, 0x8c.toByte(), 0xb0.toByte(),
            0x7f, 0x5e, 0x72, 0x2d, 0x9e.toByte(), 0xde.toByte(), 0xf3.toByte(), 0x14
        )
        val iv = byteArrayOf(
            0x01, 0x50, 0x4a, 0xf3.toByte(), 0x56, 0xe6.toByte(), 0x19, 0xcf.toByte(),
            0x2e, 0x42, 0xbb.toByte(), 0xa6.toByte(), 0x8c.toByte(), 0x3f, 0x70, 0xf9.toByte()
        )
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(padded).joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun randomId(length: Int): String = buildString(length) {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

    private fun buildApiUrl(cid: String, videoId: String, episodeUrl: String): String {
        val guid = randomId(16)
        val query = linkedMapOf(
            "vid" to videoId,
            "cid" to cid,
            "cKey" to createCKey(videoId, episodeUrl, guid),
            "encryptVer" to "8.1",
            "spcaptiontype" to "1",
            "sphls" to "2",
            "dtype" to "3",
            "defn" to "shd",
            "spsrt" to "2",
            "sphttps" to "1",
            "otype" to "json",
            "spwm" to "1",
            "hevclv" to "28",
            "drm" to "40",
            "spvideo" to "4",
            "spsfrhdr" to "100",
            "host" to "wetv.vip",
            "referer" to "wetv.vip",
            "ehost" to episodeUrl,
            "appVer" to appVersion,
            "platform" to platform,
            "guid" to guid,
            "flowid" to randomId(32)
        )
        return apiUrl + "?" + query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    private suspend fun episodeIsFree(cid: String, videoId: String, episodeUrl: String): Boolean {
        // Fail closed: the exact episode must appear in its public episode list.
        val html = try { app.get(episodeUrl, headers = webHeaders).text } catch (_: Exception) { return false }
        val document = Jsoup.parse(html, episodeUrl)
        val matching = document.select("a.play-video__link[href]").firstOrNull { anchor ->
            val ids = episodeIds(absUrl(anchor.attr("href")))
            ids?.first == cid && ids.second == videoId
        } ?: return false
        return !isPaidMarker(matching.parent()?.text()?.trim().orEmpty())
    }

    private fun subtitleLanguage(code: String): String = when (code.trim().lowercase()) {
        "ar-sa", "ar-eg" -> "ar"
        else -> code.trim().lowercase()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ids = episodeIds(data) ?: return false
        val cid = ids.first
        val videoId = ids.second
        val episodeUrl = "$mainUrl/ar/play/$cid/$videoId"

        // Never request playback information for paid, VIP, or unlisted episodes.
        if (!episodeIsFree(cid, videoId, episodeUrl)) return false

        val responseText = try {
            app.get(buildApiUrl(cid, videoId, episodeUrl), headers = apiHeaders).text
        } catch (_: Exception) {
            return false
        }
        val payload = responseText.removePrefix("QZOutputJson=").trim().removeSuffix(";")
        val root = try { JSONObject(payload) } catch (_: Exception) { return false }
        val video = root.optJSONObject("vl")?.optJSONArray("vi")?.optJSONObject(0) ?: return false

        // DRM is intentionally unsupported; reject the response if declared.
        if (video.optInt("drm", 0) != 0) return false
        val formats = root.optJSONObject("fl")?.optJSONArray("fi") ?: return false
        if (formats.length() == 0) return false
        // Reject the master playlist if any advertised quality is DRM-protected.
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: return false
            if (format.optInt("drm", 0) != 0) return false
        }

        val subtitles = root.optJSONObject("sfl")?.optJSONArray("fi") ?: JSONArray()
        for (index in 0 until subtitles.length()) {
            val item = subtitles.optJSONObject(index) ?: continue
            val language = subtitleLanguage(item.optString("lang"))
            val subtitleUrl = item.optString("url")
            if (language == "ar" && subtitleUrl.startsWith("https://")) {
                try { subtitleCallback(newSubtitleFile("ar", subtitleUrl)) } catch (_: Exception) { }
            }
        }

        val streamList = video.optJSONObject("ul")?.optJSONArray("ui") ?: return false
        val headers = mapOf(
            "User-Agent" to desktopUa,
            "Referer" to episodeUrl,
            "Origin" to mainUrl
        )
        var found = false
        for (index in 0 until streamList.length()) {
            val item = streamList.optJSONObject(index) ?: continue
            val baseUrl = item.optString("url")
            if (!baseUrl.startsWith("https://")) continue
            val suffix = item.optJSONObject("hls")?.optString("pt").orEmpty()
            val streamUrl = baseUrl + suffix
            if (!streamUrl.contains(".m3u8", true)) continue
            try {
                val links = M3u8Helper.generateM3u8("WeTV", streamUrl, episodeUrl, headers = headers)
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                    found = true
                }
            } catch (_: Exception) {
                // Try remaining public non-DRM HLS variants.
            }
        }
        return found
    }
}
