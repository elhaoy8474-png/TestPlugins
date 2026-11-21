package com.youtube

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Koda 0.12.5 ships NewPipeExtractor but its CloudStream compatibility layer
 * does not initialize the NewPipe singleton. Initialize it lazily from the
 * provider, while preserving a downloader installed by the host if present.
 */
internal object YouTubeNewPipeRuntime {
    private const val TAG = "YouTubePlugin"
    private const val SERVICE_WORKER_DATA_URL = "https://www.youtube.com/sw.js_data"
    private val initializationLock = Any()
    private val clientVersionLock = Any()
    private val jsonMapper = ObjectMapper()

    @Volatile
    private var cachedClientVersion: String? = null

    fun ensureInitialized(): Boolean = try {
        initializeIfNeeded(
            currentDownloader = { NewPipe.getDownloader() },
            initialize = { downloader -> NewPipe.init(downloader) },
            createDownloader = { YouTubeHttpDownloader() }
        )
    } catch (error: Exception) {
        Log.e(TAG, "Could not initialize NewPipe: ${error.javaClass.simpleName}: ${error.message}", error)
        false
    }

    /**
     * NewPipe 0.26.3 (embedded by cloud3 0.12.5) reads its client version from
     * sw.js. YouTube moved the value to sw.js_data; newer NewPipe versions read
     * that JSON directly. Seed the legacy private cache so the bundled 0.26.3
     * extractor can continue without changing the host application.
     */
    fun primeClientVersion(): Boolean = synchronized(clientVersionLock) {
        try {
            val downloader = NewPipe.getDownloader() ?: return@synchronized false
            val version = cachedClientVersion ?: run {
                val response = downloader.get(
                    SERVICE_WORKER_DATA_URL,
                    mapOf("Referer" to listOf("https://www.youtube.com/"))
                )
                if (response.responseCode() !in 200..299) {
                    Log.e(TAG, "YouTube sw.js_data returned HTTP ${response.responseCode()}")
                    return@synchronized false
                }
                clientVersionFromServiceWorkerData(response.responseBody())
                    ?: run {
                        Log.e(TAG, "Could not read YouTube client version from sw.js_data")
                        return@synchronized false
                    }
            }

            if (!seedLegacyClientVersion(version)) return@synchronized false
            cachedClientVersion = version
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not prime YouTube client version: ${error.javaClass.simpleName}: ${error.message}", error)
            false
        }
    }

    internal fun clientVersionFromServiceWorkerData(body: String): String? = runCatching {
        val json = body.removePrefix(")]}'").trim()
        val root = jsonMapper.readTree(json)
        root.path(0).path(2).path(0).path(0).path(16)
            .asText(null)
            ?.takeIf { it.matches(Regex("""^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$""")) }
    }.getOrNull()

    internal fun seedLegacyClientVersion(version: String): Boolean = runCatching {
        val helper = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper")
        val clientVersion = helper.getDeclaredField("clientVersion").apply { isAccessible = true }
        clientVersion.set(null, version)
        val extracted = helper.getDeclaredField("clientVersionExtracted").apply { isAccessible = true }
        extracted.setBoolean(null, true)
        true
    }.getOrDefault(false)

    internal fun initializeIfNeeded(
        currentDownloader: () -> Downloader?,
        initialize: (Downloader) -> Unit,
        createDownloader: () -> Downloader
    ): Boolean = synchronized(initializationLock) {
        if (currentDownloader() != null) return@synchronized true

        initialize(createDownloader())
        currentDownloader() != null
    }
}

/**
 * Minimal NewPipe downloader backed by Android's HttpURLConnection. It is used
 * only when the host has not provided a Downloader to NewPipe.
 */
internal class YouTubeHttpDownloader : Downloader() {
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 25_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.requestMethod = request.httpMethod()

            // Avoid compressed response bodies because NewPipe expects decoded text.
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

            request.headers().forEach { (name, values) ->
                if (!name.equals("Host", true) &&
                    !name.equals("Content-Length", true) &&
                    !name.equals("Connection", true) &&
                    !name.equals("Transfer-Encoding", true)
                ) {
                    values.forEachIndexed { index, value ->
                        if (index == 0) connection.setRequestProperty(name, value)
                        else connection.addRequestProperty(name, value)
                    }
                }
            }

            val requestUri = connection.url.toURI()
            cookieManager.get(requestUri, connection.requestProperties).forEach { (name, values) ->
                values.forEach { value -> connection.addRequestProperty(name, value) }
            }

            val payload = request.dataToSend()
            if (payload != null && request.httpMethod() !in setOf("GET", "HEAD")) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }

            val status = connection.responseCode
            val responseHeaders = connection.headerFields
                .filterKeys { key -> !key.isNullOrBlank() }
                .mapKeys { (key, _) -> key!! }
                .mapValues { (_, values) -> values ?: emptyList() }

            cookieManager.put(requestUri, responseHeaders)

            val bodyStream = try {
                connection.inputStream
            } catch (_: Exception) {
                connection.errorStream
            }
            val body = bodyStream?.use { it.readBytes().toString(charsetFrom(responseHeaders)) } ?: ""

            return Response(
                status,
                connection.responseMessage ?: "",
                responseHeaders,
                body,
                connection.url.toString()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun charsetFrom(headers: Map<String, List<String>>): Charset {
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull().orEmpty()
        val charsetName = Regex("""charset=([^;\s]+)""", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.getOrNull(1)?.trim('"')
        return runCatching { charsetName?.let(Charset::forName) ?: StandardCharsets.UTF_8 }
            .getOrDefault(StandardCharsets.UTF_8)
    }
}
