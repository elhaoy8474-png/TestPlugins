package com.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeStreamFallbackTest {
    private data class FakeMuxedStream(
        val content: String,
        val height: Int,
        val codec: String
    )

    private data class FakeStreamInfo(val videoStreams: List<FakeMuxedStream>)

    @Test fun readsMuxedVideoStreamsWhenVideoOnlyStreamsAreAbsent() {
        val info = FakeStreamInfo(
            listOf(FakeMuxedStream("https://cdn.example/video-360.mp4", 360, "avc1.42001E"))
        )
        val streams = YouTubeStreamFallback.muxedVideoStreams(info)
        assertEquals(1, streams.size)
        assertEquals("https://cdn.example/video-360.mp4", YouTubeStreamFallback.readString(streams.single(), "getContent"))
        assertEquals(360, YouTubeStreamFallback.readInt(streams.single(), "getHeight"))
        assertEquals("avc1.42001E", YouTubeStreamFallback.readString(streams.single(), "getCodec"))
    }

    @Test fun returnsEmptyListWhenMuxedStreamsAreNotAvailable() {
        assertTrue(YouTubeStreamFallback.muxedVideoStreams(FakeStreamInfo(emptyList())).isEmpty())
    }
}
