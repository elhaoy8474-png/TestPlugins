package com.faselhd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import org.jsoup.Jsoup

internal class FaselHDPlayback(
    private val mainUrl: String,
    private val name: String,
    private val desktopUa: String,
    private val webHeaders: Map<String, String>,
    private val fetch: suspend (String, Map<String, String>) -> String,
    private val externalLoad: suspend (String, String, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Boolean
) {
    private fun absoluteUrl(raw: String): String = FaselHDParser.absoluteUrl(mainUrl, raw)

    private fun qualityValue(label: String): Int {
        val number = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        return when (number) {
            2160 -> Qualities.P2160.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun collectSubtitles(
        playerHtml: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val document = Jsoup.parse(playerHtml, playerUrl)
        val found = linkedSetOf<String>()

        document.select("track[src], track[data-src], [data-subtitle]").forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-subtitle") }
            val subtitleUrl = absoluteUrl(raw)
            val language = element.attr("srclang")
                .ifBlank { element.attr("label") }
            val isArabic = language.startsWith("ar", true) ||
                language.contains("arab", true) ||
                language.contains("عربي")
            if (isArabic && (subtitleUrl.contains(".vtt", true) || subtitleUrl.contains(".srt", true))) {
                if (found.add(subtitleUrl)) {
                    subtitleCallback(newSubtitleFile("Arabic", subtitleUrl))
                }
            }
        }

    }

    private suspend fun extractPlayerLinks(
        playerUrl: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerHtml = try {
            fetch(playerUrl, webHeaders + mapOf("Referer" to episodeUrl))
        } catch (_: Exception) {
            return false
        }
        if (playerHtml.isBlank()) return false

        collectSubtitles(playerHtml, playerUrl, subtitleCallback)

        val playerDocument = Jsoup.parse(playerHtml, playerUrl)
        val buttons = playerDocument.select(".hd_btn[data-url], button[data-url*='.m3u8']")
        val sources = buttons.mapNotNull { button ->
            val raw = button.attr("data-url").trim()
            if (!raw.contains(".m3u8", true)) return@mapNotNull null
            val streamUrl = absoluteUrl(raw)
            if (!streamUrl.startsWith("https://")) return@mapNotNull null
            streamUrl to button.text().trim().ifBlank { "Auto" }
        }.distinctBy { it.first }

        if (sources.isEmpty()) {
            // The current player builds its source buttons dynamically with JavaScript.
            // Let the embedded browser observe the public HLS request instead of
            // trying to decode or reconstruct its obfuscated player script.
            return try {
                val autoPlayScript = """
                    (function () {
                        if (window.__csFaselAutoPlayInstalled) return;
                        window.__csFaselAutoPlayInstalled = true;
                        var attempts = 0;
                        var timer = setInterval(function () {
                            attempts++;
                            try {
                                var player = window.mainPlayer ||
                                    (window.jwplayer ? window.jwplayer() : null);
                                if (player && typeof player.play === 'function') {
                                    player.play(true);
                                }
                                if (player && player.getState && player.getState() === 'playing') {
                                    clearInterval(timer);
                                }
                            } catch (e) { }
                            if (attempts >= 50) clearInterval(timer);
                        }, 300);
                    })();
                """.trimIndent()
                val resolved = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8(?:$|[?#])"""),
                    script = autoPlayScript,
                    timeout = 45_000L
                ).resolveUsingWebView(
                    requestCreator("GET", playerUrl, referer = episodeUrl)
                ).first
                val streamUrl = resolved?.url?.toString()?.takeIf { it.startsWith("http") }
                    ?: return false
                val generated = M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    referer = playerUrl,
                    headers = mapOf("User-Agent" to desktopUa, "Origin" to mainUrl)
                )
                if (generated.isEmpty()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - HLS",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = playerUrl
                            quality = Qualities.Unknown.value
                            headers = mapOf("User-Agent" to desktopUa, "Origin" to mainUrl)
                        }
                    )
                } else generated.forEach(callback)
                true
            } catch (_: Exception) {
                false
            }
        }
        val autoSource = sources.firstOrNull {
            it.second.contains("auto", true) || it.second.contains("تلقائي")
        }
        val selectedSources = if (autoSource != null) listOf(autoSource) else sources
        val streamHeaders = mapOf(
            "User-Agent" to desktopUa,
            "Referer" to playerUrl,
            "Origin" to mainUrl
        )

        var found = false
        for ((streamUrl, label) in selectedSources) {
            try {
                val masterText = fetch(streamUrl, streamHeaders)
                val subtitleLines = Regex(
                    """#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*""",
                    RegexOption.IGNORE_CASE
                ).findAll(masterText)
                subtitleLines.forEach { line ->
                    val text = line.value
                    val language = Regex("""LANGUAGE="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1).orEmpty()
                    val subtitleName = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1).orEmpty()
                    val uri = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1).orEmpty()
                    if (uri.isNotBlank() && (language.startsWith("ar", true) ||
                                subtitleName.contains("arab", true) || subtitleName.contains("عربي"))) {
                        subtitleCallback(newSubtitleFile("Arabic", absoluteUrl(uri)))
                    }
                }
            } catch (_: Exception) {
                // The player may still expose usable variants through its quality buttons.
            }

            try {
                val generated = M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    playerUrl,
                    headers = streamHeaders
                )
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                    found = true
                } else {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - $label",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = playerUrl
                            quality = qualityValue(label)
                            headers = streamHeaders
                        }
                    )
                    found = true
                }
            } catch (_: Exception) {
                // Continue with any remaining publicly listed quality links.
            }
        }
        return found
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val freshEpisodeUrl = data + (if (data.contains("?")) "&" else "?") +
            "cs_nocache=" + System.currentTimeMillis()
        val episodeHtml = try {
            fetch(freshEpisodeUrl, webHeaders + mapOf("Cache-Control" to "no-cache"))
        } catch (_: Exception) {
            return false
        }
        val document = Jsoup.parse(episodeHtml, data)
        val playerUrls = linkedSetOf<String>()
        document.select(
            "[name=player_iframe][data-src], iframe[src], iframe[data-src], [data-src*='video_player']"
        ).forEach { element ->
            val raw = element.attr("data-src")
                .ifBlank { element.attr("src") }
                .trim()
            if (raw.isBlank()) return@forEach
            val playerUrl = absoluteUrl(raw)
            if (playerUrl.startsWith(mainUrl) && playerUrl.contains("video_player")) {
                playerUrls.add(playerUrl)
            }
        }

        var found = false
        for (playerUrl in playerUrls) {
            if (extractPlayerLinks(playerUrl, data, subtitleCallback, callback)) {
                found = true
            }
        }

        if (!found) {
            val externalFrames = document.select("iframe[src], iframe[data-src]").mapNotNull { frame ->
                val raw = frame.attr("src").ifBlank { frame.attr("data-src") }.trim()
                if (raw.isBlank()) return@mapNotNull null
                val frameUrl = absoluteUrl(raw)
                frameUrl.takeIf { it.startsWith("http") && !it.startsWith(mainUrl) }
            }.distinct()

            for (frameUrl in externalFrames) {
                try {
                    if (externalLoad(frameUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (_: Exception) {
                    // Keep trying the remaining publicly exposed player frames.
                }
            }
        }
        return found
    }
}
