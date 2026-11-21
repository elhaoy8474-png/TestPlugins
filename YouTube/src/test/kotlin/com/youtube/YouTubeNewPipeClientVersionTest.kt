package com.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

class YouTubeNewPipeClientVersionTest {
    @Test
    fun readsClientVersionFromNewYouTubeServiceWorkerData() {
        val body = ")]}'\n" + """[["yt.sw.adr",null,[[["nl","NL",null,null,null,null,null,null,null,null,null,null,"Android 12",null,null,null,"2.20261119.07.00"]]]]]"""
        assertEquals("2.20261119.07.00", YouTubeNewPipeRuntime.clientVersionFromServiceWorkerData(body))
    }

    @Test
    fun rejectsServiceWorkerDataWithoutAClientVersion() {
        assertEquals(null, YouTubeNewPipeRuntime.clientVersionFromServiceWorkerData(")]}'\n[]"))
    }

    @Test
    fun seedsTheLegacyNewPipeClientVersionCache() {
        try {
            YoutubeParsingHelper.resetClientVersion()
            assertTrue(YouTubeNewPipeRuntime.seedLegacyClientVersion("2.20261119.07.00"))
            val field = YoutubeParsingHelper::class.java.getDeclaredField("clientVersion")
            field.isAccessible = true
            assertEquals("2.20261119.07.00", field.get(null))
        } finally {
            YoutubeParsingHelper.resetClientVersion()
        }
    }
}
