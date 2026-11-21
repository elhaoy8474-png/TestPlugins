package com.youtube

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets.UTF_8

class YouTubeHttpDownloaderTest {
    @Test
    fun forwardsMethodHeadersAndBodyToTheLocalServer() {
        ServerSocket(0).use { server ->
            val serverThread = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val headerBytes = ByteArrayOutputStream()
                    var lastFour = ""
                    while (true) {
                        val next = input.read()
                        if (next < 0) break
                        headerBytes.write(next)
                        lastFour = (lastFour + next.toChar()).takeLast(4)
                        if (lastFour == "\r\n\r\n") break
                    }
                    val headerText = headerBytes.toString(UTF_8.name())
                    val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)")
                        .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val count = input.read(bodyBytes, offset, contentLength - offset)
                        if (count < 0) break
                        offset += count
                    }
                    val method = headerText.lineSequence().first().substringBefore(' ')
                    val probe = Regex("(?im)^X-Probe:\\s*(.+)$")
                        .find(headerText)?.groupValues?.get(1)?.trim().orEmpty()
                    val response = "$method|$probe|${bodyBytes.toString(UTF_8)}".toByteArray(UTF_8)
                    val responseHead = "HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nX-Response-Probe: verified\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(responseHead.toByteArray(UTF_8))
                        write(response)
                        flush()
                    }
                }
            }
            serverThread.start()

            val request = Request.newBuilder()
                .post("http://127.0.0.1:${server.localPort}/echo", "request-payload".toByteArray(UTF_8))
                .headers(mapOf(
                    "X-Probe" to listOf("header-value"),
                    "Content-Type" to listOf("text/plain; charset=utf-8")
                ))
                .build()

            val response = YouTubeHttpDownloader().execute(request)
            serverThread.join(5_000)

            assertEquals(200, response.responseCode())
            assertEquals("POST|header-value|request-payload", response.responseBody())
            assertEquals("verified", response.getHeader("X-Response-Probe"))
        }
    }
}
