package com.faselhd

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

internal object FaselHDParser {
    internal data class Card(
        val title: String,
        val url: String,
        val poster: String?,
        val isMovie: Boolean,
        val isAnime: Boolean
    )

    internal data class EpisodeItem(
        val title: String,
        val url: String,
        val number: Int
    )

    internal data class SeasonItem(
        val url: String,
        val number: Int
    )

    fun absoluteUrl(baseUrl: String, raw: String): String {
        val value = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
        if (value.isBlank()) return ""
        return try {
            URI(baseUrl).resolve(value).toString()
        } catch (_: Exception) {
            value
        }
    }

    private fun Element.cardTitle(): String {
        val imageTitle = selectFirst("img")?.attr("alt").orEmpty().trim()
        val title = imageTitle
            .ifBlank { attr("title").trim() }
            .ifBlank { selectFirst(".title, .h1, .h2, .h3, .h4, .h5")?.text()?.trim().orEmpty() }
            .ifBlank { text().trim() }
        return title.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun Element.cardPoster(baseUrl: String): String? {
        val image = selectFirst("img")
            ?: parent()?.selectFirst("img")
            ?: parent()?.parent()?.selectFirst("img")
            ?: return null
        val raw = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { absoluteUrl(baseUrl, it) }
    }

    private fun classify(path: String): Pair<Boolean, Boolean>? {
        val normalized = path.lowercase()
        if (normalized.startsWith("/episodes/") || normalized.startsWith("/asian-episodes/")) return null
        if (normalized.startsWith("/movies/") ||
            normalized.startsWith("/asian-movies/") ||
            normalized.startsWith("/anime-movies/") ||
            normalized.startsWith("/dubbed-movies/") ||
            normalized.startsWith("/hindi/")) return true to false
        if (normalized.startsWith("/anime/")) return false to true
        if (normalized.startsWith("/series/") ||
            normalized.startsWith("/seasons/") ||
            normalized.startsWith("/asian-series/") ||
            normalized.startsWith("/tvshows/")) return false to false
        return null
    }

    fun parseCards(html: String, baseUrl: String): List<Card> {
        val document = Jsoup.parse(html, baseUrl)
        val cards = linkedMapOf<String, Card>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach
            val url = absoluteUrl(baseUrl, href).substringBefore("#")
            val path = try { URI(url).path.orEmpty() } catch (_: Exception) { "" }
            val kind = classify(path) ?: return@forEach
            val hasCardImage = anchor.selectFirst("img") != null ||
                anchor.parent()?.selectFirst("img") != null ||
                anchor.parent()?.parent()?.selectFirst("img") != null
            if (!hasCardImage) return@forEach

            val title = anchor.cardTitle()
            if (title.isBlank() || title.length > 220) return@forEach
            cards.putIfAbsent(url, Card(title, url, anchor.cardPoster(baseUrl), kind.first, kind.second))
        }
        return cards.values.toList()
    }

    fun normalizeDigits(value: String): String = buildString {
        value.forEach { char ->
            append(
                when (char) {
                    '٠' -> '0'
                    '١' -> '1'
                    '٢' -> '2'
                    '٣' -> '3'
                    '٤' -> '4'
                    '٥' -> '5'
                    '٦' -> '6'
                    '٧' -> '7'
                    '٨' -> '8'
                    '٩' -> '9'
                    else -> char
                }
            )
        }
    }

    fun episodeNumber(value: String): Int? {
        val normalized = normalizeDigits(value)
        val match = Regex("""(?:الحلقة|حلقة|episode|ep)\s*[-:#]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?: Regex("""(\d+)\s*$""").find(normalized)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun seasonNumber(value: String): Int {
        val normalized = normalizeDigits(value)
        val numeric = Regex("""(?:الموسم|موسم|season)\s*[-:#]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numeric != null) return numeric
        val lower = normalized.lowercase()
        val words = listOf(
            "الأول" to 1, "الاول" to 1, "الأولى" to 1, "الاولى" to 1,
            "الثاني" to 2, "الثانية" to 2,
            "الثالث" to 3, "الثالثة" to 3,
            "الرابع" to 4, "الرابعة" to 4,
            "الخامس" to 5, "الخامسة" to 5,
            "السادس" to 6, "السادسة" to 6,
            "السابع" to 7, "السابعة" to 7,
            "الثامن" to 8, "الثامنة" to 8,
            "التاسع" to 9, "التاسعة" to 9,
            "العاشر" to 10, "العاشرة" to 10
        )
        return words.firstOrNull { lower.contains(it.first) }?.second ?: 1
    }


    fun parseSeasons(html: String, baseUrl: String): List<SeasonItem> {
        val document = Jsoup.parse(html, baseUrl)
        val seasons = linkedMapOf<String, SeasonItem>()
        document.select("#seasonList .seasonDiv[onclick]").forEach { element ->
            val onclick = element.attr("onclick")
            val rawUrl = Regex("""(?:window\.)?location\.href\s*=\s*['\"]([^'\"]+)['\"]""")
                .find(onclick)?.groupValues?.getOrNull(1).orEmpty()
            if (rawUrl.isBlank()) return@forEach
            val url = absoluteUrl(baseUrl, rawUrl).substringBefore("#")
            val label = element.selectFirst(".title")?.text().orEmpty()
            val number = seasonNumber(label)
            if (url.startsWith("http")) seasons.putIfAbsent(url, SeasonItem(url, number))
        }
        return seasons.values.sortedBy { it.number }
    }

    fun parseEpisodes(html: String, baseUrl: String): List<EpisodeItem> {
        val document = Jsoup.parse(html, baseUrl)
        val episodes = linkedMapOf<String, EpisodeItem>()
        document.select("#epAll a[href], .epAll a[href]").forEach { anchor ->
            val rawUrl = anchor.attr("href").trim()
            if (rawUrl.isBlank()) return@forEach
            val url = absoluteUrl(baseUrl, rawUrl).substringBefore("#")
            if (!url.contains("/episodes/") && !url.contains("/asian-episodes/")) return@forEach
            val title = anchor.text().trim().ifBlank { "الحلقة" }
            val number = episodeNumber(title) ?: episodeNumber(url.substringAfterLast("/")) ?: return@forEach
            episodes.putIfAbsent(url, EpisodeItem(title, url, number))
        }
        return episodes.values.sortedBy { it.number }
    }
}
