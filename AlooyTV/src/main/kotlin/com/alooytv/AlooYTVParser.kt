package com.alooytv

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

internal object AlooYTVParser {
    internal data class Card(
        val title: String,
        val url: String,
        val poster: String?,
        val isMovie: Boolean
    )

    internal data class Episode(
        val title: String,
        val url: String,
        val number: Int,
        val season: Int
    )

    internal data class Subtitle(
        val url: String,
        val language: String
    )

    fun movieDataUrl(url: String): String = "${url.substringBefore("#")}#alooytv-movie"

    fun isMovieDataUrl(url: String): Boolean = url.endsWith("#alooytv-movie")

    fun cleanDataUrl(url: String): String = url.substringBefore("#")

    fun absoluteUrl(baseUrl: String, rawUrl: String): String {
        val value = rawUrl.trim().replace("&amp;", "&").replace("\\/", "/")
        if (value.isBlank()) return ""
        return try { URI(baseUrl).resolve(value).toString() } catch (_: Exception) { value }
    }

    fun normalizeDigits(value: String): String = buildString {
        value.forEach { char ->
            append(when (char) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                else -> char
            })
        }
    }

    fun episodeNumber(value: String): Int? {
        val normalized = normalizeDigits(value)
        val match = Regex("""(?:episode|ep|الحلقة|حلقة)\s*#?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized) ?: Regex("""#(\d+)""").find(normalized)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun seasonNumber(value: String): Int {
        val normalized = normalizeDigits(value)
        val numeric = Regex("""(?:season|الموسم|موسم)\s*#?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numeric != null) return numeric
        val lower = normalized.lowercase()
        return when {
            "الثاني" in lower || "الثانية" in lower -> 2
            "الثالث" in lower || "الثالثة" in lower -> 3
            "الرابع" in lower || "الرابعة" in lower -> 4
            "الخامس" in lower || "الخامسة" in lower -> 5
            "السادس" in lower || "السادسة" in lower -> 6
            "السابع" in lower || "السابعة" in lower -> 7
            "الثامن" in lower || "الثامنة" in lower -> 8
            "التاسع" in lower || "التاسعة" in lower -> 9
            "العاشر" in lower || "العاشرة" in lower -> 10
            else -> 1
        }
    }

    private fun Element.poster(baseUrl: String): String? {
        val image = selectFirst("img") ?: return null
        val raw = image.attr("data-src")
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { absoluteUrl(baseUrl, it) }
    }

    fun parseCards(html: String, baseUrl: String, movieSection: Boolean): List<Card> {
        val document = Jsoup.parse(html, baseUrl)
        val cards = linkedMapOf<String, Card>()
        document.select(".movie-container .latest-movie-img-container").forEach { card ->
            val anchor = card.selectFirst(".movie-title h3 a[href]")
                ?: card.selectFirst("a[href*='/watch/']")
                ?: return@forEach
            val title = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            val url = absoluteUrl(baseUrl, anchor.attr("href"))
            if (title.isBlank() || url.isBlank()) return@forEach
            cards.putIfAbsent(url, Card(title, url, card.poster(baseUrl), movieSection))
        }
        return cards.values.toList()
    }

    fun parseEpisodes(html: String, baseUrl: String): List<Episode> {
        val document = Jsoup.parse(html, baseUrl)
        val episodes = linkedMapOf<String, Episode>()
        document.select(".season").forEach { seasonElement ->
            val seasonTitle = seasonElement.selectFirst(".movie-heading span")?.text().orEmpty()
            val season = seasonNumber(seasonTitle)
            seasonElement.select("a.btn-ep[href]").forEach { anchor ->
                val title = anchor.text().trim().ifBlank { "Episode" }
                val number = episodeNumber(title) ?: return@forEach
                val url = absoluteUrl(baseUrl, anchor.attr("href"))
                if (url.isNotBlank()) episodes.putIfAbsent(url, Episode(title, url, number, season))
            }
        }
        return episodes.values.sortedWith(compareBy<Episode> { it.season }.thenBy { it.number })
    }

    fun hasNextPage(html: String, baseUrl: String, nextPath: String): Boolean {
        val document = Jsoup.parse(html, baseUrl)
        val expected = absoluteUrl(baseUrl, nextPath)
        return document.select("a[href]").any { absoluteUrl(baseUrl, it.attr("href")) == expected }
    }

    fun parseVideoSources(html: String, baseUrl: String): List<String> {
        val document = Jsoup.parse(html, baseUrl)
        return document.select("video source[src]").mapNotNull { source ->
            val raw = source.attr("src").trim()
            if (raw.isBlank()) return@mapNotNull null
            val resolved = absoluteUrl(baseUrl, raw)
            val uri = try { URI(resolved) } catch (_: Exception) { return@mapNotNull null }
            val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
            val videoExtensions = setOf("mp4", "m4v", "m3u8", "webm", "mpd", "mov")
            val isVideo = extension in videoExtensions ||
                (extension.isBlank() && source.attr("type").lowercase().startsWith("video/"))
            val invalidPlaceholderHost = uri.host.orEmpty().substringAfterLast('.', "").all { it.isDigit() }
            resolved.takeIf { isVideo && !invalidPlaceholderHost }
        }.distinct()
    }

    fun parseSubtitles(html: String, baseUrl: String): List<Subtitle> {
        val document = Jsoup.parse(html, baseUrl)
        return document.select("video track[kind=subtitles][src], video track[kind=captions][src]")
            .mapNotNull { track ->
                val raw = track.attr("src").trim()
                if (raw.isBlank()) null else Subtitle(
                    absoluteUrl(baseUrl, raw),
                    track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                )
            }.distinctBy { it.url }
    }
}
