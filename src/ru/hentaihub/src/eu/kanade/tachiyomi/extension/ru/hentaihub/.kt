package ru.

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * Подтвердите возраст (v4.hentai-hub.net) — сгенерировано extension-forge.
 */
@Source
class (
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource() {

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30.seconds)
        .readTimeout(60.seconds)
        .rateLimit(4, 1.seconds)
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) /1.0")
        add("Accept", "application/json")

    }

    // ================================ JSON ==================================

    private fun getJson(url: HttpUrl): JsonElement = client.newCall(GET(url, headers)).execute().parseAs<JsonElement>(json)

    private fun getJson(url: String): JsonElement = getJson(url.toHttpUrl())

    private fun JsonElement.at(path: String): JsonElement? {
        if (path.isEmpty()) return this
        var current: JsonElement = this
        for (segment in path.split('.')) {
            val obj = current as? JsonObject ?: return null
            current = obj[segment] ?: return null
        }
        return current
    }

    private fun JsonElement.text(path: String): String {
        val element = at(path) ?: return ""
        return when (element) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString(", ") { it.asText() }
            else -> ""
        }
    }

    private fun JsonElement.number(path: String): Int = text(path).toDoubleOrNull()?.toInt() ?: 0

    private fun JsonElement.decimal(path: String): Double = text(path).toDoubleOrNull() ?: 0.0

    private fun JsonElement.flag(path: String): Boolean = text(path) == "true" || text(path) == "1"

    private fun JsonElement.items(path: String): List<JsonElement> = (at(path) as? JsonArray)?.toList() ?: emptyList()

    private fun JsonElement.asText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> content
        else -> ""
    }

    private fun String.absoluteUrl(useProxy: Boolean = true): String {
        if (isEmpty()) return ""
        var url = this
        if (!url.startsWith("http")) {
            url = MEDIA_BASE + (if (url.startsWith("/")) url else "/" + url)
        }
        if (MEDIA_STRIP.isNotEmpty() && url.startsWith(MEDIA_BASE + MEDIA_STRIP)) {
            url = MEDIA_BASE + url.removePrefix(MEDIA_BASE + MEDIA_STRIP)
        }
        if (useProxy && MEDIA_PROXY.isNotEmpty()) {
            url = MEDIA_PROXY.replace("{url}", java.net.URLEncoder.encode(url, "UTF-8"))
        }
        return url
    }
    // ============================== Каталог ==============================

    private fun catalogUrl(page: Int): HttpUrl = (API_BASE + LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", SIZE.toString())
            .build()

    private fun latestUrl(page: Int): HttpUrl = (API_BASE + LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", SIZE.toString())
            .build()

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(latestUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort = DEFAULT_SORT
        val builder = (API_BASE + LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", SIZE.toString())

        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())

        filters.forEach { filter ->
            when (filter) {
                else -> {}
            }
        }
        return GET(builder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val root = response.parseAs<JsonElement>(json)
        val items = root.items("")
        val mangas = items.mapNotNull { item ->
            item.toSManga()
        }
        return MangasPage(mangas, items.size >= SIZE)
    }

    // ================================ Маппинг ================================

    private fun JsonElement.toSManga(): SManga = SManga.create().apply {
        val item = this@toSManga
        url = item.text("")
        title = item.text("")
        thumbnail_url = item.text("").absoluteUrl()
        author = item.text("").takeIf { it.isNotEmpty() }
        artist = item.text("").takeIf { it.isNotEmpty() }
        description = item.text("")
        genre = item.items("genres").joinToString(", ") { it.asText() }
        status = item.text("").toSMangaStatus()
        initialized = false
    }

    private fun String.toSMangaStatus(): Int = when (this) {
        else -> SManga.UNKNOWN
    }

    private fun JsonElement.toSChapter(): SChapter = SChapter.create().apply {
        val item = this@toSChapter
        url = item.text("_id")
        name = item.text("name").ifBlank {
            "Глава " + item.text("chapterNumber").removeSuffix(".0")
        }
        chapter_number = item.decimal("chapterNumber").toFloat()
        date_upload = item.text("releaseDate").toEpochMillis()
    }

    private fun String.toEpochMillis(): Long = runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        private const val API_BASE = ""
        private const val MEDIA_BASE = "http://v4.hentai-hub.net"
        private const val MEDIA_STRIP = ""
        private const val MEDIA_PROXY = ""
        private const val LIST_PATH = ""
        private const val SIZE = 20
        private const val DEFAULT_SORT = ""
        private const val LATEST_SORT = ""

    }
}
