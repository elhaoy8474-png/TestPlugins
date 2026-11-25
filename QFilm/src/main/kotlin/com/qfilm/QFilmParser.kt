package com.qfilm

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

internal object QFilmParser {
    internal data class Card(
        val title: String,
        val url: String,
        val poster: String?
    )

    private fun absoluteUrl(baseUrl: String, raw: String): String {
        val value = raw.trim().replace("\\/", "/").replace("&amp;", "&")
        if (value.isBlank()) return ""
        return try { URI(baseUrl).resolve(value).toString() } catch (_: Exception) { value }
    }

    private fun Element.poster(baseUrl: String): String? {
        val image = selectFirst("img") ?: return null
        // QFilm's lazy loader stores the real image in data-echo while src
        // intentionally points to echo-lzld.png. Never prefer that placeholder.
        val raw = image.attr("data-echo")
            .ifBlank { image.attr("data-src") }
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("src") }
        return raw.takeIf {
            it.isNotBlank() && !it.startsWith("data:") &&
                !it.contains("echo-lzld.png", ignoreCase = true)
        }?.let { absoluteUrl(baseUrl, it) }
    }

    fun parseCards(html: String, baseUrl: String): List<Card> {
        val document = Jsoup.parse(html, baseUrl)
        val cards = linkedMapOf<String, Card>()
        document.select("a[href*='play.php?vid='], a[href*='watch.php?vid=']").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach
            val url = absoluteUrl(baseUrl, href)
            val title = anchor.attr("title").trim()
                .ifBlank { anchor.selectFirst("h3, h2, .caption")?.text()?.trim().orEmpty() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty().trim() }
                .ifBlank { anchor.text().trim() }
            if (title.isBlank() || url.isBlank()) return@forEach
            cards.putIfAbsent(url, Card(title, url, anchor.poster(baseUrl)))
        }
        return cards.values.toList()
    }
}
