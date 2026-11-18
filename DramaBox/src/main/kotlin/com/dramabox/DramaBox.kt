package com.dramabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DramaBox : MainAPI() {
    override var mainUrl = "https://www.dramabox.com"
    override var name = "DramaBox"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val api = "https://sapi.dramaboxvideo.com/drama-box"

    /*
     * Deliberately uses only public app metadata headers. Captured traffic also
     * contains per-session bearer/signature headers (tn/sn/st); those values are
     * volatile and user-specific, so this provider never stores or replays them.
     */
    private val apiHeaders = mapOf(
        "User-Agent" to "okhttp/4.12.0",
        "Accept" to "application/json",
        "Accept-Encoding" to "gzip",
        "Content-Type" to "application/json; charset=UTF-8",
        "version" to "601",
        "vn" to "6.0.1",
        "p" to "64",
        "package-name" to "com.storymatrix.drama",
        "cid" to "DAPSG1050024",
        "mchid" to "DAPSG1050024",
        "nchid" to "DRA1000042",
        "apn" to "1",
        "language" to "ar",
        "current-language" to "ar",
        "locale" to "ar_EG",
        "country-code" to "EG",
        "time-zone" to "+0200",
        "tz" to "-120"
    )

    override val mainPage = mainPageOf(
        "155" to "الرائجة",
        "41" to "الأحدث",
        "89" to "التصنيفات والترتيب"
    )

    private fun text(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun image(obj: JSONObject?): String? =
        text(obj, "bookCover", "cover", "coverUrl", "cover_url", "coverImage", "image")
            .takeIf { it.startsWith("http", true) }

    private fun bookIdFromUrl(url: String): String? =
        Regex("(?:dramabox://|bookId=)([0-9]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: url.takeIf { it.matches(Regex("[0-9]{6,}")) }

    private fun searchResponse(item: JSONObject): SearchResponse? {
        val book = item.optJSONObject("bookInfo")
            ?: item.optJSONObject("book")
            ?: item.optJSONObject("bookData")
            ?: item
        val id = text(book, "bookId", "book_id", "id")
        val title = text(book, "bookName", "name", "title", "seriesName")
        if (id.isBlank() || title.isBlank()) return null
        return newTvSeriesSearchResponse(title, "dramabox://$id", TvType.TvSeries) {
            posterUrl = image(book)
        }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject? {
        return try {
            val response = app.post("$api/$path", headers = apiHeaders, json = body)
            JSONObject(response.text)
        } catch (_: Exception) {
            null
        }
    }

    private fun channelRecords(root: JSONObject?): JSONArray? {
        val data = root?.optJSONObject("data") ?: return null
        val theater = data.optJSONObject("newTheaterList")
            ?: data.optJSONObject("theaterList")
        return theater?.optJSONArray("records")
            ?: data.optJSONArray("records")
            ?: data.optJSONArray("bookList")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelId = request.data.toIntOrNull() ?: 155
        val body = JSONObject().apply {
            put("newChannelStyle", 1)
            put("isNeedRank", 1)
            put("pageNo", page.coerceAtLeast(1))
            put("index", page.coerceAtLeast(1))
            put("channelId", channelId)
        }
        val root = post("he001/theater", body)
        val records = channelRecords(root) ?: JSONArray()
        val results = ArrayList<SearchResponse>()
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            searchResponse(item)?.let(results::add)
        }
        return newHomePageResponse(request.name, results.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim().take(80)
        if (normalized.isBlank()) return emptyList()

        val root = post("search/suggest", JSONObject().put("keyword", normalized))
        val data = root?.optJSONObject("data") ?: return emptyList()
        val suggestions = data.optJSONArray("suggestList")
            ?: data.optJSONArray("bookList")
            ?: data.optJSONArray("searchList")
            ?: return emptyList()

        val results = ArrayList<SearchResponse>()
        for (i in 0 until suggestions.length()) {
            val item = suggestions.optJSONObject(i) ?: continue
            val kind = text(item, "kind", "type", "suggestType")
            if (kind.equals("actor", true) || kind.equals("performer", true)) continue
            searchResponse(item)?.let(results::add)
        }
        return results.distinctBy { it.url }
    }

    private fun batchBody(bookId: String, episodeIndex: Int, preLoad: Boolean): JSONObject =
        JSONObject().apply {
            put("boundaryIndex", episodeIndex)
            put("comingPlaySectionId", -1)
            put("index", episodeIndex)
            put("currencyPlaySourceName", "首页发现_الرائجة_推荐列表")
            put("rid", "")
            put("enterReaderChapterIndex", 0)
            put("loadDirection", 0)
            put("startUpKey", UUID.randomUUID().toString())
            put("bookId", bookId)
            put("currencyPlaySource", "discover_167_rec")
            put("needEndRecommend", 0)
            put("preLoad", preLoad)
            put("pullCid", "")
        }

    private suspend fun batchLoad(bookId: String, episodeIndex: Int, preLoad: Boolean = false): JSONObject? =
        post("chapterv2/batch/load", batchBody(bookId, episodeIndex, preLoad))

    override suspend fun load(url: String): LoadResponse? {
        val bookId = bookIdFromUrl(url) ?: return null

        // batch/load supplies title metadata; chapterv2/detail supplies all
        // chapter indices and paywall flags.
        val batchRoot = batchLoad(bookId, 0, false)
        val batchData = batchRoot?.optJSONObject("data")
        val detailRoot = post(
            "chapterv2/detail",
            JSONObject()
                .put("needRecommend", false)
                .put("from", "book_ablum")
                .put("bookId", bookId)
        )
        val detailData = detailRoot?.optJSONObject("data")
        val chapterRows = detailData?.optJSONArray("list")
            ?: batchData?.optJSONArray("chapterList")
            ?: JSONArray()

        if (chapterRows.length() == 0 && batchData == null) return null

        val title = text(batchData, "bookName", "title").ifBlank { "DramaBox - $bookId" }
        val poster = text(batchData, "bookCover").takeIf { it.startsWith("http") }
        val plot = text(batchData, "introduction", "description", "synopsis")
        val count = batchData?.optInt("chapterCount", chapterRows.length())
            ?: chapterRows.length()

        val episodes = ArrayList<Episode>()
        for (i in 0 until chapterRows.length()) {
            val chapter = chapterRows.optJSONObject(i) ?: continue
            val chapterId = text(chapter, "chapterId", "chapter_id")
            if (chapterId.isBlank()) continue
            val chapterIndex = chapter.optInt("chapterIndex", i)
            val isCharge = chapter.optInt("isCharge", 0) == 1
            val episodeData = JSONObject().apply {
                put("bookId", bookId)
                put("chapterId", chapterId)
                put("index", chapterIndex)
                put("isCharge", isCharge)
            }.toString()
            val number = chapterIndex + 1
            episodes.add(newEpisode(episodeData) {
                name = text(chapter, "chapterName", "name").ifBlank { "الحلقة $number" } +
                    if (isCharge) " 🔒" else ""
                episode = number
                season = 1
                posterUrl = poster
                description = if (isCharge) "حلقة مدفوعة - افتحها من التطبيق الرسمي" else "متاحة"
            })
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot.ifBlank { "عدد الحلقات: $count" }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = try { JSONObject(data) } catch (_: Exception) { return false }
        val bookId = episode.optString("bookId")
        val chapterId = episode.optString("chapterId")
        val index = episode.optInt("index", 0)
        if (bookId.isBlank() || chapterId.isBlank()) return false

        // Do not attempt to resolve chapters marked as paid by the detail API.
        if (episode.optBoolean("isCharge", false)) return false

        val root = batchLoad(bookId, index, false) ?: return false
        val dataObject = root.optJSONObject("data") ?: return false
        val chapters = dataObject.optJSONArray("chapterList") ?: return false
        var selected: JSONObject? = null
        for (i in 0 until chapters.length()) {
            val candidate = chapters.optJSONObject(i) ?: continue
            if (candidate.optString("chapterId") == chapterId) {
                selected = candidate
                break
            }
        }
        val chapter = selected ?: return false
        if (chapter.optBoolean("chargeChapter", false) ||
            chapter.optInt("isCharge", 0) == 1
        ) return false

        val cdns = chapter.optJSONArray("cdnList") ?: return false
        val sources = ArrayList<Triple<String, String, Int>>()
        for (cdnIndex in 0 until cdns.length()) {
            val cdn = cdns.optJSONObject(cdnIndex) ?: continue
            val domain = cdn.optString("cdnDomain").ifBlank { "CDN " + (cdnIndex + 1) }
            val paths = cdn.optJSONArray("videoPathList") ?: continue
            for (j in 0 until paths.length()) {
                val path = paths.optJSONObject(j) ?: continue
                if (path.optInt("isVipEquity", 0) == 1) continue
                val videoUrl = path.optString("videoPath")
                if (!videoUrl.startsWith("https://", true)) continue
                val quality = path.optInt("quality", 0)
                if (quality <= 0) continue
                sources.add(Triple(domain, videoUrl, quality))
            }
        }

        val primaryDomain = cdns.optJSONObject(0)?.optString("cdnDomain").orEmpty()
        val ordered = sources.distinctBy { it.second }
            .sortedWith(
                compareByDescending<Triple<String, String, Int>> { it.third }
                    .thenBy { it.first != primaryDomain }
            )

        var found = false
        for ((domain, videoUrl, quality) in ordered) {
            try {
                callback(
                    newExtractorLink(
                        name,
                        "DramaBox $quality" + "p • " + domain,
                        videoUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        referer = "$mainUrl/"
                    }
                )
                found = true
            } catch (_: Exception) {
                // Keep attempting the other quality/CDN alternatives.
            }
        }
        return found
    }
}
