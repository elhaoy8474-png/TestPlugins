package com.youtube

import android.util.Log

/** Reflection adapter for NewPipe's StreamInfo, which is bundled by CloudStream at runtime. */
internal object YouTubeStreamFallback {
    fun loadStreamInfo(watchUrl: String): Any? = runCatching {
        val streamInfo = Class.forName("org.schabi.newpipe.extractor.stream.StreamInfo")
        streamInfo.getMethod("getInfo", String::class.java).invoke(null, watchUrl)
    }.onFailure { error ->
        Log.e("YouTubePlugin", "NewPipe StreamInfo extraction failed: ${error.javaClass.simpleName}: ${error.message}", error)
    }.getOrNull()

    fun muxedVideoStreams(info: Any): List<Any> = readList(info, "getVideoStreams")

    fun subtitles(info: Any): List<Any> = readList(info, "getSubtitles")

    fun readString(target: Any, getter: String): String? = runCatching {
        target.javaClass.getMethod(getter).invoke(target)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun readInt(target: Any, getter: String): Int? = runCatching {
        (target.javaClass.getMethod(getter).invoke(target) as? Number)?.toInt()
    }.getOrNull()

    private fun readList(target: Any, getter: String): List<Any> = runCatching {
        (target.javaClass.getMethod(getter).invoke(target) as? Iterable<*>)
            ?.filterNotNull()
            ?: emptyList()
    }.getOrDefault(emptyList())
}
