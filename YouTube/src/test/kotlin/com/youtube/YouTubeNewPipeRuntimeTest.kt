package com.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

class YouTubeNewPipeRuntimeTest {
    private class FakeDownloader : Downloader() {
        override fun execute(request: Request): Response =
            Response(200, "OK", emptyMap(), "", request.url())
    }

    @Test
    fun initializesNewPipeWhenNoDownloaderExists() {
        var active: Downloader? = null
        val created = FakeDownloader()

        val initialized = YouTubeNewPipeRuntime.initializeIfNeeded(
            currentDownloader = { active },
            initialize = { active = it },
            createDownloader = { created }
        )

        assertTrue(initialized)
        assertSame(created, active)
    }

    @Test
    fun keepsAnExistingApplicationDownloader() {
        val existing = FakeDownloader()
        var initialized = false

        val ready = YouTubeNewPipeRuntime.initializeIfNeeded(
            currentDownloader = { existing },
            initialize = { initialized = true },
            createDownloader = { FakeDownloader() }
        )

        assertTrue(ready)
        assertFalse(initialized)
    }

    @Test
    fun reportsFailureIfInitializationDoesNotInstallDownloader() {
        val ready = YouTubeNewPipeRuntime.initializeIfNeeded(
            currentDownloader = { null },
            initialize = { },
            createDownloader = { FakeDownloader() }
        )

        assertFalse(ready)
    }
}
