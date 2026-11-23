package com.viu

import org.junit.Assert.assertTrue
import org.junit.Test

class ViuSearchRequestTest {
    @Test
    fun buildsCurrentViuSearchEndpointWithEncodedKeywordAndRegion() {
        val url = ViuSearchRequest.buildUrl(
            api = "https://api-gateway-global.viu.com",
            query = "تزلج بالحب & King",
            areaId = "1003",
            languageFlagId = "6",
            countryCode = "eg"
        )

        assertTrue(url.startsWith("https://api-gateway-global.viu.com/api/mobile?r=%2Fsearch%2Fvideo"))
        assertTrue(url.contains("keyword=%D8%AA%D8%B2%D9%84%D8%AC+%D8%A8%D8%A7%D9%84%D8%AD%D8%A8+%26+King"))
        assertTrue(url.contains("page=1&limit=20"))
        assertTrue(url.contains("area_id=1003&language_flag_id=6"))
        assertTrue(url.contains("countryCode=EG&platform_flag_label=web"))
    }
}
