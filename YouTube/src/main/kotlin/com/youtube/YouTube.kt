package com.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.YoutubeExtractor
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class YouTube : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(term, "UTF-8")
        val html = app.get("https://m.youtube.com/results?search_query=$encoded", headers = requestHeaders).text
        return YouTubeParser.parseSearchResults(html).map { video ->
            val label = if (video.channel.isNullOrBlank()) video.title else "${video.title} • ${video.channel}"
            newMovieSearchResponse(label, YouTubeParser.watchUrl(video.videoId)!!, TvType.Movie) {
                posterUrl = video.thumbnail
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:.*[?&]v=|embed/|shorts/|live/|v/))([A-Za-z0-9_-]{11})")
            .find(url)?.groupValues?.get(1) ?: return null
        val watchUrl = YouTubeParser.watchUrl(videoId) ?: return null
        val html = app.get(watchUrl, headers = requestHeaders).text
        val details = YouTubeParser.parseDetails(html, videoId)
        return newMovieLoadResponse(details.title, watchUrl, TvType.Movie, watchUrl) {
            posterUrl = details.poster
            plot = details.description
            tags = listOfNotNull(details.channel)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.takeIf { it.startsWith("https://") } ?: YouTubeParser.watchUrl(data) ?: return false
        var found = false

        // Keep CloudStream's standard extractor as the primary path.
        try {
            YoutubeExtractor().getUrl(watchUrl, null, subtitleCallback) { link ->
                found = true
                callback(link)
            }
        } catch (_: Exception) {
            // Some YouTube responses expose muxed video+audio streams only.
            // The bundled extractor currently returns no link for that case.
        }

        // Fallback for YouTube videos that have muxed streams but no video-only streams.
        if (!found) {
            val info = YouTubeStreamFallback.loadStreamInfo(watchUrl)
            val streams = info?.let(YouTubeStreamFallback::muxedVideoStreams).orEmpty()
            for (stream in streams) {
                val streamUrl = YouTubeStreamFallback.readString(stream, "getContent")
                    ?.takeIf { it.startsWith("http") } ?: continue
                val height = YouTubeStreamFallback.readInt(stream, "getHeight")
                val codec = YouTubeStreamFallback.readString(stream, "getCodec")
                    ?.substringBefore('.')?.uppercase()?.takeIf { it.isNotBlank() }
                callback(newExtractorLink(name, "YouTube ${height?.let { "${it}p" } ?: "Video"}${codec?.let { " $it" } ?: ""}", streamUrl) {
                    quality = height ?: Qualities.Unknown.value
                    referer = watchUrl
                })
                found = true
            }

            info?.let(YouTubeStreamFallback::subtitles)?.forEach { subtitle ->
                val subtitleUrl = YouTubeStreamFallback.readString(subtitle, "getContent")
                    ?.takeIf { it.startsWith("http") } ?: return@forEach
                val language = YouTubeStreamFallback.readString(subtitle, "getDisplayLanguageName")
                    ?: YouTubeStreamFallback.readString(subtitle, "getLanguageTag")
                    ?: "Unknown"
                subtitleCallback(newSubtitleFile(language, subtitleUrl))
            }
        }

        return found
    }
}
