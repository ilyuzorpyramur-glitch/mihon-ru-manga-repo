package ru.tomilolib

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
 * Tomilo-lib (tomilo-lib.ru) — сгенерировано extension-forge.
 *
 * 
 */
@Source
class TomiloLib(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .connectTimeout(30.seconds)
            .readTimeout(60.seconds)
            .rateLimit(4, 1.seconds)
            .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) TomiloLib/1.0")
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

    private fun String.absoluteUrl(): String = when {
        isEmpty() -> ""
        startsWith("http") -> this
        startsWith("/") -> MEDIA_BASE + this
        else -> MEDIA_BASE + "/" + this
    }

    // ============================== Настройки ==============================
    private val showAdult get() = preferences.getBoolean("show_adult", false)

    private val hidePaid get() = preferences.getBoolean("hide_paid", true)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context)
            .apply {
                key = "show_adult"
                title = "Показывать контент 18+"
                setDefaultValue(false)
            }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context)
            .apply {
                key = "hide_paid"
                title = "Скрывать платные главы"
                setDefaultValue(true)
            }.let(screen::addPreference)
    }

    // ============================== Каталог ==============================

    private fun catalogUrl(page: Int): HttpUrl = (API_BASE + LIST_PATH)
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", SIZE.toString())
        .addQueryParameter("order", "desc")
        .addQueryParameter("sortBy", DEFAULT_SORT)
        .build()

    private fun latestUrl(page: Int): HttpUrl = (API_BASE + LIST_PATH)
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", SIZE.toString())
        .addQueryParameter("order", "desc")
        .addQueryParameter("sortBy", LATEST_SORT)
        .build()

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(latestUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        var sort = DEFAULT_SORT
        val builder =
            (API_BASE + LIST_PATH)
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", SIZE.toString())
                .addQueryParameter("order", "desc")

        if (query.isNotBlank()) builder.addQueryParameter("search", query.trim())

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    filter.selected
                        .takeIf { it.isNotEmpty() }
                        ?.let { builder.addQueryParameter("type", it) }
                }
                is StatusFilter -> {
                    filter.selected
                        .takeIf { it.isNotEmpty() }
                        ?.let { builder.addQueryParameter("status", it) }
                }
                is SortFilter -> {
                    sort = filter.selected
                }
                is GenreFilter -> {
                    filter.state
                        .filter { it.state }
                        .forEach { builder.addQueryParameter(GENRE_PARAM, it.id) }
                }
                else -> {}
            }
        }
        if (sort.isNotEmpty()) builder.addQueryParameter("sortBy", sort)
        return GET(builder.build(), headers)
    }

    private fun parseList(response: Response): MangasPage {
        val root = response.parseAs<JsonElement>(json)
        val items = root.items("data.titles")
        val mangas =
            items.mapNotNull { item ->
                if (!showAdult && item.flag("isAdult")) return@mapNotNull null
                item.toSManga()
            }
        return MangasPage(mangas, root.number("data.pagination.page") < root.number("data.pagination.pages"))
    }

    // =============================== Детали ===============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(API_BASE + DETAILS_PATH.replace("{id}", manga.url), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<JsonElement>(json)
        val item = root.at("data") ?: root
        return item.toSManga()
    }

    // ================================ Главы =================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapterItems = mutableListOf<JsonElement>()
        var page = 1
        var totalPages = 1
        do {
            val root =
                getJson(API_BASE + CHAPTERS_PATH.replace("{id}", manga.url) + "?titleId=" + manga.url + "&limit=500" + "&page=" + page)
            chapterItems += root.items("data.chapters")
            totalPages = root.number("data.pagination.pages")
            page++
        } while (page <= totalPages)

        val filtered =
            chapterItems.filterNot { item ->
                hidePaid && item.flag("isPaid") &&
                    item.number("unlockPrice") > 0
            }

        filtered.map { it.toSChapter() }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // =============================== Страницы ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET(API_BASE + PAGES_PATH.replace("{chapter}", chapter.url), headers)

    override fun pageListParse(response: Response): List<Page> = response
        .parseAs<JsonElement>(json)
        .items("data.pages")
        .mapIndexed { index, item ->
            Page(index, imageUrl = item.asText().absoluteUrl())
        }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ================================ Маппинг ================================

    private fun JsonElement.toSManga(): SManga = SManga.create().apply {
        val item = this@toSManga
        url = item.text("_id")
        title = item.text("name")
        thumbnail_url = item.text("coverImage").absoluteUrl()
        author = item.text("author").takeIf { it.isNotEmpty() }
        artist = item.text("artist").takeIf { it.isNotEmpty() }
        description = item.text("description")
        genre = item.items("genres").joinToString(", ") { it.asText() }
        status = item.text("status").toSMangaStatus()
        initialized = false
    }

    private fun String.toSMangaStatus(): Int = when (this) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "pause" -> SManga.ON_HIATUS
        "frozen" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun JsonElement.toSChapter(): SChapter = SChapter.create().apply {
        val item = this@toSChapter
        url = item.text("_id")
        name =
            item.text("name").ifBlank {
                "Глава " + item.text("chapterNumber").removeSuffix(".0")
            }
        chapter_number = item.decimal("chapterNumber").toFloat()
        date_upload = item.text("releaseDate").toEpochMillis()
    }

    private fun String.toEpochMillis(): Long = runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ================================ Фильтры ================================

    private class SortFilter :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("Популярное", "Дата обновления", "Дата добавления", "Рейтинг", "По алфавиту", "Год выпуска", "Кол-во глав"),
        ) {
        val selected get() = arrayOf("views", "updatedAt", "createdAt", "averageRating", "name", "releaseYear", "totalChapters")[state]
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Тип",
            arrayOf("Все", "Манга", "Манхва", "Маньхуа", "Комикс"),
        ) {
        val selected get() = arrayOf("", "manga", "manhwa", "manhua", "comic")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Статус",
            arrayOf("Любой", "Онгоинг", "Завершён", "Приостановлен"),
        ) {
        val selected get() = arrayOf("", "ongoing", "completed", "pause")[state]
    }

    private class GenreFilter :
        Filter.Group<Genre>(
            "Жанры",
            listOf(
                Genre("Action", "391b0423-d847-456f-aff0-8b0cfc03066b"),
                Genre("Adventure", "87cc87cd-a395-47af-b27a-93258283bbc6"),
                Genre("Boys' Love", "5920b825-4181-4a17-beeb-9918b0ff7a30"),
                Genre("Comedy", "4d32cc48-9f00-4cca-9b5a-a839f0764984"),
                Genre("Crime", "5ca48985-9a9d-4bd8-be29-80dc0303db72"),
                Genre("Drama", "b9af3a63-f058-46de-a9a0-e0c13906197a"),
                Genre("Fantasy", "cdc58593-87dd-415e-bbc0-2ec27bf404cc"),
                Genre("Girls' Love", "a3c67850-4684-404e-9b7f-c69850ee5da6"),
                Genre("Historical", "33771934-028e-4cb3-8744-691e866a923e"),
                Genre("Horror", "cdad7e68-1419-41dd-bdce-27753074a640"),
                Genre("Isekai", "ace04997-f6bd-436e-b261-779182193d3d"),
                Genre("Magical Girls", "81c836c9-914a-4eca-981a-560dad663e73"),
                Genre("Mecha", "50880a9d-5440-4732-9afb-8f457127e836"),
                Genre("Medical", "c8cbe35b-1b2b-4a3f-9c37-db84c4514856"),
                Genre("Mystery", "ee968100-4191-4968-93d3-f82d72be7e46"),
                Genre("Philosophical", "b1e97889-25b4-4258-b28b-cd7f4d28ea9b"),
                Genre("Psychological", "3b60b75c-a2d7-4860-ab56-05f391bb889c"),
                Genre("Romance", "423e2eae-a7a2-4a8b-ac03-a8351462d71d"),
                Genre("Sci-Fi", "256c8bd9-4904-4360-bf4f-508a76d67183"),
                Genre("Slice of Life", "e5301a23-ebd9-49dd-a0cb-2add944c7fe9"),
                Genre("Sports", "69964a64-2f90-4d33-beeb-f3ed2875eb4c"),
                Genre("Superhero", "7064a261-a137-4d3a-8848-2d385de3a99c"),
                Genre("Thriller", "07251805-a27e-4d59-b488-f0bfbec15168"),
                Genre("Tragedy", "f8f62932-27da-4fe4-8ee1-6779a8c5edba"),
                Genre("Wuxia", "acc803a4-c95a-4c22-86fc-eb6b582d82a2"),
            ),
        )

    private class Genre(
        name: String,
        val id: String,
    ) : Filter.CheckBox(name)

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    companion object {
        private const val API_BASE = "https://tomilo-lib.ru/api"
        private const val MEDIA_BASE = "https://tomilolib.s3.regru.cloud"
        private const val LIST_PATH = "/titles"
        private const val SIZE = 30
        private const val DEFAULT_SORT = "views"
        private const val LATEST_SORT = "updatedAt"
        private const val TOTAL_PAGES_PATH = "data.pagination.pages"
        private const val CURRENT_PAGE_PATH = "data.pagination.page"
        private const val DETAILS_PATH = "/titles/{id}"
        private const val CHAPTERS_PATH = "/chapters"
        private const val PAGES_PATH = "/chapters/{chapter}"
        private const val GENRE_PARAM = "genres"
    }
}
