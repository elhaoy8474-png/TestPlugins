package com.youtube

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup

internal object YouTubeParser {
    data class VideoResult(
        val videoId: String,
        val title: String,
        val channel: String?,
        val thumbnail: String?,
        val duration: String?
    )

    private val mapper = ObjectMapper()
    private val validId = Regex("^[A-Za-z0-9_-]{11}$")

    fun watchUrl(videoId: String): String? =
        videoId.takeIf { validId.matches(it) }?.let { "https://www.youtube.com/watch?v=$it" }

    private fun jsStringValue(source: String, variable: String): String? {
        val marker = "var $variable = "
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) return null
        var index = markerIndex + marker.length
        while (index < source.length && source[index].isWhitespace()) index++
        if (index >= source.length || source[index] != '\'') return null
        index++
        val output = StringBuilder()
        var escaped = false
        while (index < source.length) {
            val char = source[index++]
            if (escaped) {
                output.append('\\').append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '\'') {
                return output.toString()
            } else {
                output.append(char)
            }
        }
        return null
    }

    private fun decodeInitialData(source: String): JsonNode? {
        val encoded = jsStringValue(source, "ytInitialData") ?: return null
        val normalized = encoded
            .replace("\\\\x", "\\x")
            .replace(Regex("\\\\x([0-9a-fA-F]{2})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            .replace("\\\\", "\\")
        return runCatching { mapper.readTree(normalized) }.getOrNull()
    }

    private fun text(node: JsonNode?): String? {
        if (node == null || node.isMissingNode || node.isNull) return null
        val runs = node.path("runs")
        if (runs.isArray) return runs.mapNotNull { it.path("text").asText(null) }.joinToString("").trim().takeIf { it.isNotBlank() }
        return node.path("simpleText").asText(null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun thumbnail(node: JsonNode?): String? {
        val thumbs = node?.path("thumbnails") ?: return null
        if (!thumbs.isArray || thumbs.isEmpty) return null
        return thumbs.last().path("url").asText(null)?.replace("\\u0026", "&")
    }

    private fun parseRenderer(renderer: JsonNode): VideoResult? {
        val id = renderer.path("videoId").asText("").ifBlank {
            renderer.path("navigationEndpoint").path("watchEndpoint").path("videoId").asText("")
        }
        if (!validId.matches(id)) return null
        val title = text(renderer.path("headline"))
            ?: text(renderer.path("title"))
            ?: text(renderer.path("overlayMetadata").path("primaryText"))
            ?: return null
        val channel = text(renderer.path("shortBylineText"))
            ?: text(renderer.path("ownerText"))
            ?: text(renderer.path("longBylineText"))
        val image = thumbnail(renderer.path("thumbnail").path("thumbnails"))
            ?: thumbnail(renderer.path("thumbnail"))
        val duration = text(renderer.path("lengthText"))
        return VideoResult(id, title, channel, image, duration)
    }

    fun parseSearchResults(html: String): List<VideoResult> {
        val root = decodeInitialData(html) ?: return emptyList()
        val results = linkedMapOf<String, VideoResult>()
        fun visit(node: JsonNode) {
            if (node.isObject) {
                listOf("videoWithContextRenderer", "videoRenderer", "compactVideoRenderer", "gridVideoRenderer").forEach { key ->
                    val renderer = node.get(key)
                    if (renderer != null && renderer.isObject) {
                        parseRenderer(renderer)?.let { results.putIfAbsent(it.videoId, it) }
                    }
                }
                val fields = node.fields()
                while (fields.hasNext()) visit(fields.next().value)
            } else if (node.isArray) {
                node.forEach(::visit)
            }
        }
        visit(root)
        return results.values.toList()
    }

    data class VideoDetails(val title: String, val description: String?, val poster: String?, val channel: String?)

    fun parseDetails(html: String, fallbackId: String): VideoDetails {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().removeSuffix(" - YouTube").trim().ifBlank { "YouTube video $fallbackId" }
        val description = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val channel = doc.selectFirst("link[itemprop=name]")?.attr("content")?.takeIf { it.isNotBlank() }
        return VideoDetails(title, description, poster, channel)
    }
}
