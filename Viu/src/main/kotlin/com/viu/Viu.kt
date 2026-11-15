package com.viu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

class Viu : MainAPI() {
    override var mainUrl = "https://www.viu.com"
    override var name = "Viu"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val region = "eg"
    private val api = "https://api-gateway-global.viu.com"
    private val webHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )
    private var areaId = "1000"
    private var languageFlagId = "3"
    private var bearerToken: String? = null

    override val mainPage = mainPageOf(
        "$mainUrl/ott/$region/en-us" to "Viu - المحتوى المتاح"
    )

    private fun json(text: String): JSONObject? = try { JSONObject(text) } catch (_: Exception) { null }

    private fun firstText(item: JSONObject?, vararg keys: String): String {
        if (item == null) return ""
        for (key in keys) {
            val value = item.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun productId(url: String): String? =
        Regex("(?:viu://product/|/vod/)([0-9]+)").find(url)?.groupValues?.get(1)
            ?: url.takeIf { it.matches(Regex("[0-9]+")) }

    private fun poster(item: JSONObject): String? =
        item.optString("series_image_url").takeIf { it.startsWith("http") }
            ?: item.optString("cover_image_url").takeIf { it.startsWith("http") }
            ?: item.optString("product_cover_image_url").takeIf { it.startsWith("http") }
            ?: item.optString("product_image_url").takeIf { it.startsWith("http") }
            ?: item.optString("image_url").takeIf { it.startsWith("http") }

    private fun itemResponse(item: JSONObject, fallbackName: String = ""): SearchResponse? {
        val id = item.optString("product_id").ifBlank {
            item.optString("id").ifBlank { item.optString("series_id") }
        }
        if (id.isBlank() || id == "0") return null
        // Viu home grids use "title" and "series_name"; they do not use "name".
        // Prefer the series title for episodic products and the product title as fallback.
        val title = firstText(item, "series_name", "name", "title", "synopsis", "product_synopsis")
            .ifBlank { fallbackName.trim() }
        if (title.isBlank()) return null
        val image = poster(item)
        val isMovie = item.optInt("is_movie", 0) == 1
        return if (isMovie) {
            newMovieSearchResponse(title, "viu://product/$id", TvType.Movie) { posterUrl = image }
        } else {
            newTvSeriesSearchResponse(title, "viu://product/$id", TvType.TvSeries) { posterUrl = image }
        }
    }

    private suspend fun loadSiteSettings() {
        try {
            val url = "$api/api/mobile?r=%2Fsetting%2Fquery2&platform_flag_label=web"
            val root = json(app.get(url, headers = webHeaders).text) ?: return
            val area = root.optJSONObject("server")?.optJSONObject("area") ?: return
            areaId = area.optString("area_id").ifBlank { areaId }
            val languages = area.optJSONArray("language")
            val first = languages?.optJSONObject(0)
            languageFlagId = first?.optString("language_flag_id")?.ifBlank { languageFlagId } ?: languageFlagId
        } catch (_: Exception) { }
    }

    private suspend fun token(): String? {
        bearerToken?.let { return it }
        return try {
            val uuid = UUID.randomUUID().toString()
            val body = JSONObject().apply {
                put("countryCode", region.uppercase())
                put("deviceId", uuid)
                put("uuid", uuid)
                put("platform", "browser")
                put("platformFlagLabel", "web")
                put("language", "en")
                put("carrierId", "0")
            }.toString()
            val url = "$api/api/auth/token?v=${System.currentTimeMillis()}"
            val headers = webHeaders + mapOf("Content-Type" to "application/json", "Accept" to "application/json")
            val response = json(app.post(url, headers = headers, json = JSONObject(body)).text) ?: return null
            val raw = response.optString("token").ifBlank {
                response.optJSONObject("data")?.optString("token").orEmpty()
            }
            if (raw.isBlank()) null else {
                bearerToken = if (raw.startsWith("Bearer ")) raw else "Bearer $raw"
                bearerToken
            }
        } catch (_: Exception) { null }
    }

    // Match the proven Viu OTT detail request used by the Kodi Viu add-on.
    // Both area_id and language_flag_id are required for region/language-specific
    // series metadata, and ut=0 is part of the established request.
    private fun productUrl(id: String): String =
        "$mainUrl/ott/$region/index.php?area_id=$areaId&language_flag_id=$languageFlagId&r=vod/ajax-detail&platform_flag_label=web&product_id=$id&ut=0"

    private suspend fun productData(id: String): JSONObject? {
        return try {
            val root = json(app.get(productUrl(id), headers = webHeaders).text) ?: return null
            root.optJSONObject("data") ?: root.takeIf {
                it.has("current_product") || it.has("series")
            }
        } catch (_: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadSiteSettings()
        val items = mutableListOf<SearchResponse>()
        // The live Viu web/mobile gateway exposes the home catalogue at /home/index.
        // The former /ott/eg/index.php?r=home/ajax-index URL is not the catalogue API.
        val url = "$api/api/mobile?r=/home/index&platform_flag_label=phone&language_flag_id=$languageFlagId&ut=0&area_id=$areaId"
        val root = try {
            json(app.get(url, headers = webHeaders).text)
        } catch (_: Exception) {
            null
        }
        val data = root?.optJSONObject("data")
        val banners = data?.optJSONArray("banner") ?: JSONArray()
        for (i in 0 until banners.length()) {
            val item = banners.optJSONObject(i) ?: continue
            itemResponse(item)?.let(items::add)
        }
        val grids = data?.optJSONArray("grid") ?: JSONArray()
        for (i in 0 until grids.length()) {
            val grid = grids.optJSONObject(i) ?: continue
            val products = grid.optJSONArray("product") ?: continue
            for (j in 0 until products.length()) {
                val item = products.optJSONObject(j) ?: continue
                itemResponse(item, grid.optString("name"))?.let(items::add)
            }
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadSiteSettings()
        return try {
            val searchApi = "$api/api/mobile?r=%2Fsearch%2Fvideo&language_flag_id=$languageFlagId&cpreference_id=&platform_flag_label=web"
            val body = JSONObject().apply {
                put("keyword", JSONArray().put(query))
                put("url", searchApi)
            }.toString()
            val url = "$mainUrl/ott/$region/index.php?r=vod/jsonp&area_id=$areaId&language_flag_id=$languageFlagId&cpreference_id="
            val headers = webHeaders + mapOf("Content-Type" to "application/json")
            val root = json(app.post(url, headers = headers, json = JSONObject(body)).text) ?: return emptyList()
            val data = root.optJSONObject("data") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            val series = data.optJSONArray("series") ?: JSONArray()
            for (i in 0 until series.length()) {
                val item = series.optJSONObject(i) ?: continue
                itemResponse(item)?.let(out::add)
            }
            val products = data.optJSONArray("product") ?: JSONArray()
            for (i in 0 until products.length()) {
                val item = products.optJSONObject(i) ?: continue
                itemResponse(item, item.optString("series_name"))?.let(out::add)
            }
            out.distinctBy { it.url }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadSiteSettings()
        val id = productId(url) ?: return null
        val data = productData(id) ?: return null
        val current = data.optJSONObject("current_product")
            ?: data.optJSONObject("product")
            ?: return null
        val series = data.optJSONObject("series") ?: JSONObject()
        val title = firstText(series, "name", "series_name", "title")
            .ifBlank { firstText(current, "series_name", "name", "title", "synopsis", "product_synopsis") }
            .ifBlank { "Viu - $id" }
        val image = poster(current) ?: poster(series)
        val description = firstText(current, "description", "synopsis", "product_synopsis")
            .ifBlank { firstText(series, "description", "synopsis") }
        val isMovie = current.optInt("is_movie", 0) == 1 ||
            current.optString("is_movie").equals("true", true)
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, "viu://product/$id") {
                posterUrl = image
                plot = description
            }
        }
        val products = series.optJSONArray("product")
            ?: data.optJSONArray("product")
            ?: series.optJSONArray("episodes")
            ?: JSONArray()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until products.length()) {
            val item = products.optJSONObject(i) ?: continue
            val episodeId = firstText(item, "product_id", "id")
            if (episodeId.isBlank() || episodeId == "0") continue
            val number = firstText(item, "number", "product_number", "episode_number").toIntOrNull()
            val episodeName = firstText(item, "synopsis", "name", "title")
                .ifBlank { "الحلقة ${number ?: i + 1}" }
            episodes.add(newEpisode("viu://product/$episodeId") {
                name = episodeName
                episode = number ?: (i + 1)
                season = 1
                posterUrl = poster(item) ?: image
            })
        }
        val sorted = episodes.sortedWith(
            compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
        ).toMutableList()
        if (sorted.isEmpty()) {
            val number = firstText(current, "number", "product_number", "episode_number").toIntOrNull()
            val currentId = firstText(current, "product_id", "id").ifBlank { id }
            sorted.add(newEpisode("viu://product/$currentId") {
                name = firstText(current, "synopsis", "name", "title")
                    .ifBlank { "الحلقة ${number ?: 1}" }
                episode = number ?: 1
                season = 1
                posterUrl = image
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            posterUrl = image
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadSiteSettings()
        val id = productId(data) ?: return false
        val currentData = productData(id) ?: return false
        val product = currentData.optJSONObject("current_product") ?: return false
        val ccsId = product.optString("ccs_product_id")
        if (ccsId.isBlank()) return false
        val auth = token() ?: return false
        val playbackUrl = "$api/api/playback/distribute?platform_flag_label=web&area_id=$areaId&language_flag_id=$languageFlagId&countryCode=${region.uppercase()}&ccs_product_id=$ccsId"
        val headers = webHeaders + mapOf("Authorization" to auth, "Accept" to "application/json")
        val playback = try { json(app.get(playbackUrl, headers = headers).text) } catch (_: Exception) { null } ?: return false
        val status = playback.optJSONObject("status")
        if (status != null && status.optInt("code", 0) > 0) return false
        val stream = playback.optJSONObject("data")?.optJSONObject("stream") ?: return false
        val streamUrls = linkedSetOf<String>()
        for (key in listOf("url4", "url3", "url2", "url", "airplayurl")) {
            val value = stream.opt(key)
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val candidate = value.optString(keys.next())
                        if (candidate.startsWith("http")) streamUrls.add(candidate)
                    }
                }
                is String -> if (value.startsWith("http")) streamUrls.add(value)
            }
        }
        var found = false
        for (streamUrl in streamUrls) {
            try {
                if (streamUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8("Viu", streamUrl, mainUrl, headers = webHeaders).forEach(callback)
                } else {
                    callback(newExtractorLink("Viu", "Viu", streamUrl, ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    })
                }
                found = true
            } catch (_: Exception) { }
        }
        val subtitles = product.optJSONArray("subtitle") ?: JSONArray()
        for (i in 0 until subtitles.length()) {
            val sub = subtitles.optJSONObject(i) ?: continue
            val subUrl = sub.optString("subtitle_url")
            if (subUrl.startsWith("http")) {
                subtitleCallback(SubtitleFile(sub.optString("code").ifBlank { "Viu" }, subUrl))
            }
        }
        return found
    }
}
