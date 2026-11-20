package com.elhaoy8474.faselhd
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element

class FaselHD : MainAPI() {
 override var mainUrl = "https://www.fasel-hd.co"
 override var name = "FaselHD"
 override var lang = "ar"
 override val hasMainPage = true
 override val usesWebView = true
 override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
 override val mainPage = mainPageOf(
  "$mainUrl/movies" to "الأفلام",
  "$mainUrl/series" to "المسلسلات",
  "$mainUrl/recent_series" to "أحدث المسلسلات",
  "$mainUrl/asian-series" to "المسلسلات الآسيوية",
  "$mainUrl/anime" to "الأنمي",
  "$mainUrl/anime-movies" to "أفلام الأنمي"
 )
 private fun String.firstNumber(): Int? = Regex("""\d+""").find(this)?.value?.toIntOrNull()
 private fun Element.toSearchResult(): SearchResponse? {
  val anchor = selectFirst("a[href]") ?: return null
  val url = anchor.attr("abs:href").ifBlank { return null }
  val image = anchor.selectFirst("img")
  val title = selectFirst(".postInner .h1")?.text()?.trim()?.ifBlank { null } ?: image?.attr("alt")?.trim().orEmpty()
  if (title.isBlank()) return null
  val poster = image?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.takeIf { it.startsWith("http") }
  val quality = selectFirst(".quality")?.text()
  val type = when {
   url.contains("/movies/") || url.contains("/anime-movies/") -> TvType.Movie
   url.contains("/anime") -> TvType.Anime
   url.contains("/asian") -> TvType.AsianDrama
   else -> TvType.TvSeries
  }
  return newMovieSearchResponse(title, url, type) {
   this.posterUrl = poster
   this.quality = getQualityFromString(quality)
  }
 }
 override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
  val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page"
  val response = app.get(pageUrl, timeout = 30)
  if (!response.isSuccessful) return newHomePageResponse(request.name, emptyList())
  return newHomePageResponse(request.name, response.document.select("#postList .postDiv").mapNotNull { it.toSearchResult() })
 }
 override suspend fun search(query: String): List<SearchResponse> {
  val response = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", timeout = 30)
  if (!response.isSuccessful) return emptyList()
  return response.document.select("#postList .postDiv").mapNotNull { it.toSearchResult() }
 }
 override suspend fun load(url: String): LoadResponse {
  val doc = app.get(url, timeout = 30).document
  val title = doc.selectFirst(".singleInfo h1.title")?.text()?.trim()
   ?: doc.selectFirst("title")?.text()?.substringBefore(" - فاصل")?.trim() ?: url.substringAfterLast('/')
  val poster = Regex("""url\(['"]?(https?://[^)'"]+)""").find(doc.selectFirst(".singlePage")?.attr("style").orEmpty())?.groupValues?.getOrNull(1)
   ?: doc.selectFirst(".posterImg img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
  val plot = doc.selectFirst(".singleDesc p")?.text()?.trim()
  val tags = doc.select("#singleList a[rel=tag]").map { it.text() }.filter { it.isNotBlank() }
  val year = doc.select("#singleList .col-xl-6").firstOrNull { it.text().contains("سنة الإنتاج") }?.text()?.firstNumber()
  val duration = doc.select("#singleList .col-xl-6").firstOrNull { it.text().contains("مدة الفيلم") }?.text()?.firstNumber()
  val episodeLinks = doc.select(".epAll a[href]")
  val isSeries = episodeLinks.isNotEmpty() || url.contains("/series/") || url.contains("/asian-series/") || url.contains("/anime/")
  if (!isSeries) return newMovieLoadResponse(title, url, TvType.Movie, url) {
   this.posterUrl = poster; this.plot = plot; this.year = year; this.duration = duration; this.tags = tags
  }
  val episodes = episodeLinks.map { link ->
   newEpisode(link.attr("abs:href")) {
    this.name = link.text().trim().ifBlank { null }
    this.episode = link.text().firstNumber()
    this.season = doc.selectFirst(".seasonDiv.active .title")?.text()?.firstNumber() ?: 1
   }
  }.distinctBy { it.data }.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })
  return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
   this.posterUrl = poster; this.plot = plot; this.year = year; this.duration = duration; this.tags = tags
  }
 }
 override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
  val doc = app.get(data, timeout = 30).document
  val frame = doc.selectFirst("iframe[name=player_iframe]")
  val playerUrl = frame?.attr("data-src")?.takeIf { it.startsWith("http") } ?: frame?.attr("src")?.takeIf { it.startsWith("http") } ?: return false
  return try {
   val result = WebViewResolver(Regex("""master\.m3u8""")).resolveUsingWebView(requestCreator("GET", playerUrl, referer = data)).first
   val streamUrl = result?.url?.toString() ?: return false
   M3u8Helper.generateM3u8(name, streamUrl, referer = playerUrl).forEach(callback)
   true
  } catch (_: Exception) { false }
 }
}
@CloudstreamPlugin
class FaselHDPlugin : Plugin() {
 override fun load(context: Context) { registerMainAPI(FaselHD()) }
}
