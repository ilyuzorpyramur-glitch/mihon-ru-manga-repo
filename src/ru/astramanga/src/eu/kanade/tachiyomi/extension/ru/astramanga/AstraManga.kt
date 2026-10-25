package ru.astramanga

import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
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
import rx.Observable
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * AstraManga (astramanga.org) — сгенерировано extension-forge.
 *
 * API без ключа. Главы по веткам перевода: главная ветка или самая большая. Жанры — /genres, фильтр по id.
 */
@Source
class AstraManga(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30.seconds)
        .readTimeout(60.seconds)
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AstraManga/1.0")
        add("Accept", "application/json")

    }

    // ================================ JSON ==================================

    private fun getJson(url: HttpUrl): JsonElement =
        client.newCall(GET(url, headers)).execute().parseAs<JsonElement>(json)

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

    private fun JsonElement.items(path: String): List<JsonElement> =
        (at(path) as? JsonArray)?.toList() ?: emptyList()

    private fun JsonElement.asText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> content
        else -> ""
    }

    private fun String.absoluteUrl(): String = when {
        isEmpty() -> ""
        startsWith("http") -> this
        startsWith("/") -> MEDIA_BASE + this
        else -> MEDIA_BASE + "/" + this
    }
    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    // ============================== Каталог ==============================

    private fun catalogUrl(page: Int): HttpUrl =
        (API_BASE + LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", SIZE.toString())
            .addQueryParameter("sort", DEFAULT_SORT)
            .build()

    private fun latestUrl(page: Int): HttpUrl =
        (API_BASE + LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", SIZE.toString())
            .addQueryParameter("sort", LATEST_SORT)
            .build()

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(latestUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort = DEFAULT_SORT
        val builder = (API_BASE + LIST_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", SIZE.toString())

        if (query.isNotBlank()) builder.addQueryParameter("query", query.trim())

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> filter.selected.takeIf { it.isNotEmpty() }
                    ?.let { builder.addQueryParameter("type", it) }
                is StatusFilter -> filter.selected.takeIf { it.isNotEmpty() }
                    ?.let { builder.addQueryParameter("status", it) }
                is SortFilter -> sort = filter.selected
                is GenreFilter -> filter.state.filter { it.state }
                    .forEach { builder.addQueryParameter(GENRE_PARAM, it.id) }
                else -> {}
            }
        }
        if (sort.isNotEmpty()) builder.addQueryParameter("sort", sort)
        return GET(builder.build(), headers)
    }

    private fun parseList(response: Response): MangasPage {
        val root = response.parseAs<JsonElement>(json)
        val items = root.items("data.titles")
        val mangas = items.mapNotNull { item ->
            item.toSManga()
        }
        return MangasPage(mangas, root.number("data.current_page") < root.number("data.total_pages"))
    }

    // =============================== Детали ===============================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(API_BASE + DETAILS_PATH.replace("{id}", manga.url), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<JsonElement>(json)
        val item = root.at("data") ?: root
        return item.toSManga()
    }

    // ================================ Главы =================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val step1 = getJson(API_BASE + STEP1_PATH.replace("{id}", manga.url))
        val step1Items = step1.items("data.branches")
        val picked1 = step1Items.firstOrNull { it.flag("is_main") } ?: step1Items.maxByOrNull { it.number("count_chapters") }
        val branchId = picked1?.text("id") ?: ""
        val branchCount = picked1?.text("count_chapters") ?: ""

        val chapterItems = getJson(API_BASE + CHAPTERS_PATH.replace("{branch_id}", branchId).replace("{branch_count}", branchCount).replace("{id}", manga.url) + "?page_size=" + branchCount).items("data.items")

        chapterItems.map { it.toSChapter() }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // =============================== Страницы ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(API_BASE + PAGES_PATH.replace("{chapter}", chapter.url), headers)

    override fun pageListParse(response: Response): List<Page> =
        response.parseAs<JsonElement>(json).items("data.pages")
            .mapIndexed { index, item ->
                Page(index, imageUrl = item.text("image_url").absoluteUrl())
            }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ================================ Маппинг ================================

    private fun JsonElement.toSManga(): SManga = SManga.create().apply {
        val item = this@toSManga
        url = item.text("slug")
        title = item.text("name")
        thumbnail_url = item.text("cover_image").absoluteUrl()
        author = item.text("").takeIf { it.isNotEmpty() }
        artist = item.text("").takeIf { it.isNotEmpty() }
        description = item.text("synopsis")
        genre = item.items("genres").joinToString(", ") { it.asText() }
        status = item.text("status").toSMangaStatus()
        initialized = false
    }

    private fun String.toSMangaStatus(): Int = when (this) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "paused" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun JsonElement.toSChapter(): SChapter = SChapter.create().apply {
        val item = this@toSChapter
        url = item.text("id")
        name = item.text("name").ifBlank {
            "Глава " + item.text("number").removeSuffix(".0")
        }
        chapter_number = item.decimal("number").toFloat()
        date_upload = item.text("").toEpochMillis()
    }

    private fun String.toEpochMillis(): Long = runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ================================ Фильтры ================================

    private class SortFilter :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("По популярности", "По дате обновления", "По дате добавления", "По рейтингу", "По названию"),
        ) {
        val selected get() = arrayOf("-popularity", "-updated_at", "-created_at", "-rating", "name")[state]
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Тип",
            arrayOf("Все", "Манга", "Манхва", "Маньхуа"),
        ) {
        val selected get() = arrayOf("", "manga", "manhwa", "manhua")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Статус",
            arrayOf("Любой", "Онгоинг", "Завершён", "Приостановлен"),
        ) {
        val selected get() = arrayOf("", "ongoing", "completed", "paused")[state]
    }

    private class GenreFilter :
        Filter.Group<Genre>(
            "Жанры",
            listOf(
                Genre("Аниме", "1"),
                Genre("Антиутопия", "2"),
                Genre("Апокалиптический", "3"),
                Genre("Арт", "4"),
                Genre("Безумие", "5"),
                Genre("Боевик", "6"),
                Genre("Боевые искусства", "7"),
                Genre("Вестерн", "8"),
                Genre("Военное", "9"),
                Genre("Выживание", "10"),
                Genre("Гарем", "11"),
                Genre("Героическое фэнтези", "12"),
                Genre("Гуро", "13"),
                Genre("Гэг-юмор", "14"),
                Genre("Детектив", "15"),
                Genre("Детское", "16"),
                Genre("Дзёсей", "17"),
                Genre("Драма", "18"),
                Genre("Завоевание мира", "19"),
                Genre("Исекай", "20"),
            ),
        )

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    companion object {
        private const val API_BASE = "https://api.astramanga.org/api/v1"
        private const val MEDIA_BASE = "https://astramanga.org/media"
        private const val LIST_PATH = "/search"
        private const val SIZE = 30
        private const val DEFAULT_SORT = "-popularity"
        private const val LATEST_SORT = "-updated_at"
        private const val TOTAL_PAGES_PATH = "data.total_pages"
        private const val CURRENT_PAGE_PATH = "data.current_page"
        private const val DETAILS_PATH = "/titles/{id}"
        private const val STEP1_PATH = "/titles/{id}/branches"
        private const val CHAPTERS_PATH = "/branches/{branch_id}/chapters"
        private const val PAGES_PATH = "/chapters/{chapter}/pages"
        private const val GENRE_PARAM = "genres"

    }
}
