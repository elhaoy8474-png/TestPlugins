package com.viu

import java.net.URLEncoder

/**
 * Builds the public Viu mobile search URL used by both the full search
 * screen and CloudStream's quick-search suggestions.
 */
internal object ViuSearchRequest {
    fun buildUrl(
        api: String,
        query: String,
        areaId: String,
        languageFlagId: String,
        countryCode: String
    ): String {
        val keyword = URLEncoder.encode(query.trim(), "UTF-8")
        return "$api/api/mobile?r=%2Fsearch%2Fvideo&keyword=$keyword&page=1&limit=20" +
            "&area_id=$areaId&language_flag_id=$languageFlagId" +
            "&countryCode=${countryCode.uppercase()}&platform_flag_label=web&ut=0"
    }
}
