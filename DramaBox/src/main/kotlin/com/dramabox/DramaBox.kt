package com.dramabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

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
        "country-code" to "US",
        "time-zone" to "+0200",
        "tz" to "-120"
    )

    override val mainPage = mainPageOf(
        "167" to "الرائجة",
        "85" to "الأحدث",
        "98" to "الأعلى ترتيبًا",
        "152" to "التصنيفات"
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
        text(obj, "coverWap", "bookCover", "cover", "coverUrl", "cover_url", "coverImage", "image")
            .takeIf { it.startsWith("http", true) }

    private fun newRecSessionId(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
            JSONObject(response.text).also { result ->
                val apiStatus = result.optInt("status", 0)
                if (apiStatus != 0) {
                    android.util.Log.w("DramaBox", "API status $apiStatus for $path")
                }
            }
        } catch (error: Exception) {
            // Never log request headers or tokens; only the endpoint and exception type.
            android.util.Log.w("DramaBox", "Request failed for $path (${error.javaClass.simpleName})")
            null
        }
    }

    private fun channelRecords(root: JSONObject?): JSONArray? {
        val data = root?.optJSONObject("data") ?: return null
        val theater = data.optJSONObject("newTheaterList")
            ?: data.optJSONObject("theaterList")
        theater?.optJSONArray("records")?.let { if (it.length() > 0) return it }

        // For You returns columns, each containing its own bookList.
        val columns = data.optJSONArray("columnVoList")
        if (columns != null) {
            val flattened = JSONArray()
            for (i in 0 until columns.length()) {
                val books = columns.optJSONObject(i)?.optJSONArray("bookList") ?: continue
                for (j in 0 until books.length()) {
                    books.optJSONObject(j)?.let { flattened.put(it) }
                }
            }
            if (flattened.length() > 0) return flattened
        }

        // Recommendation and ranking responses can wrap books in tag cards.
        val recommendations = data.optJSONObject("recommendList")
        val ranked = JSONArray()
        val recommendationRows = recommendations?.optJSONArray("records")
        if (recommendationRows != null) {
            for (i in 0 until recommendationRows.length()) {
                val row = recommendationRows.optJSONObject(i) ?: continue
                val books = row.optJSONObject("tagCardVo")?.optJSONArray("tagBooks")
                if (books != null) {
                    for (j in 0 until books.length()) books.optJSONObject(j)?.let(ranked::put)
                } else ranked.put(row)
            }
        }
        if (ranked.length() > 0) return ranked

        return data.optJSONArray("records")
            ?: data.optJSONArray("bookList")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelId = request.data.toIntOrNull() ?: 167
        val pageNo = page.coerceAtLeast(1)

        // recommendBook is verified for the general/popular feed only.
        // Do not pass other channel IDs as specialColumnId: that parameter
        // is a different field in the official API.
        val isPopularFeed = channelId == 167
        val firstBody = if (isPopularFeed) {
            JSONObject().apply {
                put("isNeedRank", 1)
                put("specialColumnId", 0)
                put("pageNo", pageNo)
                put("recSessionId", newRecSessionId())
            }
        } else {
            JSONObject().apply {
                put("newChannelStyle", 1)
                put("isNeedRank", 1)
                put("pageNo", pageNo)
                put("index", 1)
                put("channelId", channelId)
                put("recSessionId", newRecSessionId())
            }
        }

        val firstPath = if (isPopularFeed) "he001/recommendBook" else "he001/theater"
        var records = channelRecords(post(firstPath, firstBody)) ?: JSONArray()

        // Retain a theater fallback for the popular feed if recommendations
        // are unavailable, without changing the requests for other channels.
        if (isPopularFeed && records.length() == 0) {
            val fallbackBody = JSONObject().apply {
                put("newChannelStyle", 1)
                put("isNeedRank", 1)
                put("pageNo", pageNo)
                put("index", 1)
                put("channelId", channelId)
                put("recSessionId", newRecSessionId())
            }
            records = channelRecords(post("he001/theater", fallbackBody)) ?: JSONArray()
        }

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

        val body = JSONObject().apply {
            put("searchSource", "")
            put("sortType", 1)
            put("synSwitch", 1)
            put("pageNo", 1)
            put("pageSize", 20)
            put("from", "search_result")
            put("keyword", normalized)
        }
        val root = post("search/search", body)
        val data = root?.optJSONObject("data") ?: return emptyList()
        val suggestions = data.optJSONArray("searchResultList")
            ?: data.optJSONArray("records")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("searchList")
            ?: data.optJSONArray("suggestList")
            ?: data.optJSONArray("bookList")
            ?: return emptyList()

        val results = ArrayList<SearchResponse>()
        for (i in 0 until suggestions.length()) {
            val item = suggestions.optJSONObject(i) ?: continue
            val kind = text(item, "kind", "type", "suggestType")
            if (kind.equals("actor", true) || kind.equals("performer", true)) continue

            // Search results may wrap the actual book object or group books under a card.
            val nestedBooks = item.optJSONArray("bookList")
                ?: item.optJSONObject("tagCardVo")?.optJSONArray("tagBooks")
            if (nestedBooks != null) {
                for (j in 0 until nestedBooks.length()) {
                    nestedBooks.optJSONObject(j)?.let { searchResponse(it)?.let(results::add) }
                }
            } else {
                searchResponse(item)?.let(results::add)
            }
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
            put("startUpKey", newRecSessionId())
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
