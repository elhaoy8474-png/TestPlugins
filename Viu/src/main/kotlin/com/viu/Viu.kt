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
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val region = "eg"
    private val api = "https://api-gateway-global.viu.com"
    private val webHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )
    private var areaId = "1003"
    private var languageFlagId = "6"
    private var bearerToken: String? = null
    private var tokenExpiryTime: Long = 0L

    override val mainPage = mainPageOf(
        "$mainUrl/ott/$region/en-us" to "Viu - المحتوى المتاح"
    )

    private fun json(text: String): JSONObject? = try { JSONObject(text) } catch (_: Exception) { null }

    // Diagnostic-only: record public entitlement/error metadata, never tokens or stream URLs.
    private fun logPlaybackDiagnostic(stage: String, root: JSONObject?, product: JSONObject? = null) {
        val rootCode = firstText(root, "code", "status", "error_code").ifBlank { "none" }
        val data = root?.optJSONObject("data")
        val dataKeys = data?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") ?: "none"
        val entitlementKeys = listOf(
            "is_vip", "is_premium", "is_free", "is_subscribe", "is_locked",
            "is_lock", "is_pay", "vip", "pay_type", "purchase_type",
            "product_type", "subscription_status", "entitlement"
        )
        val flags = entitlementKeys.mapNotNull { key ->
            if (product?.has(key) == true) "$key=${product.opt(key)}" else null
        }.joinToString(",").ifBlank { "not_returned" }
        println("[VIU-DIAG] stage=$stage code=$rootCode data_keys=$dataKeys entitlement=$flags")
    }

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

    private fun seriesId(url: String): String? =
        Regex("viu://series/([0-9]+)").find(url)?.groupValues?.get(1)

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
        val isMovie = item.optInt("is_movie", 0) == 1 ||
            item.optString("is_movie").equals("true", true)
        if (isMovie) {
            val movieId = firstText(item, "product_id", "id")
            if (movieId.isBlank()) return null
            return newMovieSearchResponse(title, "viu://product/$movieId", TvType.Movie) {
                posterUrl = image
            }
        }
        // Viu series pages are keyed by series_id, not by the banner's product_id.
        val showId = firstText(item, "series_id", "id", "product_id")
        if (showId.isBlank()) return null
        return newTvSeriesSearchResponse(title, "viu://series/$showId", TvType.TvSeries) {
            posterUrl = image
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
        // Viu access tokens expire; refresh before expiry to avoid later playback failures.
        val now = System.currentTimeMillis() / 1000
        bearerToken?.let { if (now < tokenExpiryTime - 60L) return it }
        return try {
            val uuid = UUID.randomUUID().toString()
            val payload = mapOf(
                "countryCode" to region.uppercase(),
                "platform" to "android",
                "platformFlagLabel" to "phone",
                "language" to languageFlagId,
                "deviceId" to uuid,
                "dataTrackingDeviceId" to UUID.randomUUID().toString(),
                "osVersion" to "33",
                "appVersion" to "2.23.0",
                "buildVersion" to "790",
                "carrierId" to "0",
                "carrierName" to "null",
                "appBundleId" to "com.vuclip.viu",
                "flavour" to "all"
            )
            val url = "$api/api/auth/token?v=${System.currentTimeMillis()}"
            val headers = webHeaders + mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json"
            )
            val response = json(app.post(url, headers = headers, data = payload).text) ?: return null
            val tokenData = response.optJSONObject("data")
            val raw = response.optString("token").ifBlank {
                tokenData?.optString("token").orEmpty()
            }
            if (raw.isBlank()) {
                bearerToken = null
                tokenExpiryTime = 0L
                null
            } else {
                val expiresIn = response.optLong("expires_in",
                    tokenData?.optLong("expires_in", 3600L) ?: 3600L
                ).coerceAtLeast(120L)
                bearerToken = if (raw.startsWith("Bearer ")) raw else "Bearer $raw"
                tokenExpiryTime = now + expiresIn
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
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        loadSiteSettings()
        return try {
            val url = ViuSearchRequest.buildUrl(
                api = api,
                query = keyword,
                areaId = areaId,
                languageFlagId = languageFlagId,
                countryCode = region
            )
            val root = json(app.get(url, headers = webHeaders + mapOf(
                "Accept" to "application/json"
            )).text) ?: return emptyList()
            val data = root.optJSONObject("data") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()

            // The current Viu search API returns separate series, movie and product arrays.
            for (key in listOf("series", "movie", "micro_drama", "product")) {
                val items = data.optJSONArray(key) ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    itemResponse(item, firstText(item, "series_name", "name"))?.let(out::add)
                }
            }
            out.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query)

    override suspend fun load(url: String): LoadResponse? {
        loadSiteSettings()

        // Viu series are fetched by series_id through /vod/product-list.
        // The home banner's product_id identifies an episode/product and is
        // not a reliable identifier for the whole series.
        val showId = seriesId(url)
        if (showId != null) {
            val listUrl = "$api/api/mobile?platform_flag_label=phone&os_flag_id=2&r=%2Fvod%2Fproduct-list&series_id=$showId&size=1000&area_id=$areaId&language_flag_id=$languageFlagId"
            val headers = try {
                val auth = token()
                if (auth.isNullOrBlank()) webHeaders else webHeaders + mapOf("Authorization" to auth)
            } catch (_: Exception) {
                webHeaders
            }
            val root = try { json(app.get(listUrl, headers = headers).text) } catch (_: Exception) { null }
                ?: return null
            val data = root.optJSONObject("data") ?: return null
            val products = data.optJSONArray("product_list")
                ?: data.optJSONArray("products")
                ?: return null
            val episodes = mutableListOf<Episode>()
            var detectedTitle = ""
            var seriesImage: String? = null
            var seriesDescription = ""
            for (i in 0 until products.length()) {
                val item = products.optJSONObject(i) ?: continue
                val episodeId = firstText(item, "product_id", "id")
                if (episodeId.isBlank() || episodeId == "0") continue
                val number = firstText(item, "number", "product_number", "episode_number").toIntOrNull()
                val synopsis = firstText(item, "synopsis", "name", "title")
                if (detectedTitle.isBlank()) {
                    detectedTitle = firstText(item, "series_category_name", "series_name", "series_name_en")
                    if (detectedTitle.isBlank() && synopsis.contains("-")) {
                        detectedTitle = synopsis.substringBefore("-").trim()
                    }
                }
                if (seriesImage == null) seriesImage = poster(item)
                if (seriesDescription.isBlank()) seriesDescription = firstText(item, "description")
                val episodeTitle = synopsis.ifBlank { "الحلقة ${number ?: i + 1}" }
                val ccsId = firstText(item, "ccs_product_id")
                if (ccsId.isBlank()) continue
                val episodeData = JSONObject().put("ccs", ccsId).put("pid", episodeId).toString()
                episodes.add(newEpisode(episodeData) {
                    name = episodeTitle
                    episode = number ?: (i + 1)
                    season = 1
                    posterUrl = poster(item) ?: seriesImage
                    description = firstText(item, "description")
                })
            }
            if (episodes.isEmpty()) return null
            val sorted = episodes.sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE })
            val title = detectedTitle.ifBlank { "Viu - $showId" }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                posterUrl = seriesImage
                plot = seriesDescription
            }
        }

        val id = productId(url) ?: return null
        val data = productData(id) ?: return null
        val current = data.optJSONObject("current_product") ?: return null
        val series = data.optJSONObject("series") ?: JSONObject()
        val title = firstText(series, "name", "series_name", "title")
            .ifBlank { firstText(current, "series_name", "name", "title", "synopsis", "product_synopsis") }
            .ifBlank { "Viu - $id" }
        val image = poster(current) ?: poster(series)
        val description = firstText(current, "description", "synopsis", "product_synopsis")
            .ifBlank { firstText(series, "description", "synopsis") }
        if (current.optInt("is_movie", 0) == 1) {
            return newMovieLoadResponse(title, url, TvType.Movie, "viu://product/$id") {
                posterUrl = image
                plot = description
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadSiteSettings()

        // Episodes carry both identifiers returned by /vod/product-list.
        // Movies keep the legacy product URL and are handled by the fallback.
        val episodeInfo = try { JSONObject(data) } catch (_: Exception) { null }
        val ccsId = episodeInfo?.optString("ccs").orEmpty()
        val episodeProductId = episodeInfo?.optString("pid").orEmpty()
        if (ccsId.isBlank() || episodeProductId.isBlank()) {
            val id = productId(data) ?: return false
            val currentData = productData(id) ?: return false
            val product = currentData.optJSONObject("current_product") ?: return false
            val movieCcsId = product.optString("ccs_product_id")
            if (movieCcsId.isBlank()) return false
            return loadViuPlayback(movieCcsId, id, subtitleCallback, callback)
        }
        return loadViuPlayback(ccsId, episodeProductId, subtitleCallback, callback)
    }

    private suspend fun loadViuPlayback(
        ccsId: String,
        productId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val auth = token() ?: return false
        val headers = webHeaders + mapOf(
            "Authorization" to auth,
            "Accept" to "application/json",
            "User-Agent" to "okhttp/4.12.0"
        )

        // Fetch episode detail using the mobile API, as required by Viu's player flow.
        val detailUrl = "$api/api/mobile?platform_flag_label=phone&os_flag_id=2&r=%2Fvod%2Fdetail&product_id=$productId&language_flag_id=$languageFlagId&area_id=$areaId&countryCode=${region.uppercase()}"
        val detail = try { json(app.get(detailUrl, headers = headers).text) } catch (_: Exception) { null }
        val product = detail?.optJSONObject("data")?.optJSONObject("current_product")
        logPlaybackDiagnostic("detail", detail, product)
        val subtitles = product?.optJSONArray("subtitle") ?: JSONArray()
        for (i in 0 until subtitles.length()) {
            val sub = subtitles.optJSONObject(i) ?: continue
            val subUrl = firstText(sub, "url", "subtitle_url")
            if (subUrl.startsWith("http")) {
                subtitleCallback(SubtitleFile(firstText(sub, "iso_code", "code", "name").ifBlank { "Viu" }, subUrl))
            }
        }

        val playbackUrl = "$api/api/playback/distribute?ccs_product_id=$ccsId&platform_flag_label=phone&language_flag_id=$languageFlagId&area_id=$areaId"
        val playback = try { json(app.get(playbackUrl, headers = headers).text) } catch (_: Exception) { null }
        if (playback == null) {
            logPlaybackDiagnostic("playback", null)
            return false
        }
        val stream = playback.optJSONObject("data")?.optJSONObject("stream")
        if (stream == null) {
            logPlaybackDiagnostic("playback_no_stream", playback)
            return false
        }
        logPlaybackDiagnostic("playback_stream_present", playback)
        val streamUrls = linkedSetOf<String>()
        for (key in listOf("url", "url2", "url3", "url4", "airplayurl")) {
            when (val value = stream.opt(key)) {
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
                    M3u8Helper.generateM3u8("Viu", streamUrl, mainUrl, headers = headers).forEach(callback)
                } else {
                    callback(newExtractorLink("Viu", "Viu", streamUrl, ExtractorLinkType.VIDEO) {
                        referer = "$mainUrl/"
                        quality = Qualities.Unknown.value
                    })
                }
                found = true
            } catch (_: Exception) { }
        }
        return found
    }
}
