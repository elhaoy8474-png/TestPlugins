package com.youtube

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Optional live probe. Run with YOUTUBE_LIVE_TEST=1; it deliberately does not
 * make ordinary unit-test runs depend on YouTube's network/bot-check behavior.
 */
class YouTubeNewPipeNetworkProbeTest {
    @Test
    fun extractsStreamsFromACommonPublicVideo() {
        assumeTrue(System.getenv("YOUTUBE_LIVE_TEST") == "1")
        assertTrue("NewPipe initialization failed", YouTubeNewPipeRuntime.ensureInitialized())
        assertTrue("Could not update the legacy NewPipe client version", YouTubeNewPipeRuntime.primeClientVersion())

        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        val hasVideo = !info.videoOnlyStreams.isNullOrEmpty() || !info.videoStreams.isNullOrEmpty()
        val hasHls = !info.hlsUrl.isNullOrBlank()
        assertTrue("NewPipe returned no video or HLS streams", hasVideo || hasHls)
    }
}
