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
            // Лимит только для API: обложки грузятся пачкой (30 штук в сетке),
            // и общий лимит на все хосты их притормаживал.
            .rateLimit(6, 1.seconds) { it.encodedPath.startsWith("/api") }
            .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        )
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
        // CDN и сайт отдают файлы из корня бакета: /uploads/titles/.. -> /titles/..
        if (MEDIA_STRIP.isNotEmpty()) {
            for (host in MEDIA_HOSTS) {
                val prefix = host + MEDIA_STRIP
                if (url.startsWith("$prefix/")) {
                    url = host + url.removePrefix(prefix)
                    break
                }
            }
        }
        if (useProxy && MEDIA_PROXY.isNotEmpty()) {
            url = MEDIA_PROXY.replace("{url}", java.net.URLEncoder.encode(url, "UTF-8"))
        }
        return url
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

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

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
            Page(index, imageUrl = item.asText().absoluteUrl(useProxy = false))
        }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ================================ Маппинг ================================

    private fun JsonElement.toSManga(): SManga = SManga.create().apply {
        val item = this@toSManga
        url = item.text("_id")
        title = item.text("name")
        thumbnail_url = item.text("coverImage").absoluteUrl().let { url ->
            // ?cover=2 — сбрасывает кэш обложек Mihon (ключ = URL), чтобы
            // заглушки, сохранённые старыми версиями, не залипали
            when {
                url.isEmpty() -> url
                url.contains('?') -> url
                else -> "$url?cover=2"
            }
        }
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
                Genre("Азартные игры", "Азартные игры"),
                Genre("Алхимия", "Алхимия"),
                Genre("Альтернативное настоящее", "Альтернативное настоящее"),
                Genre("Амнезия / Потеря памяти", "Амнезия / Потеря памяти"),
                Genre("Ангелы", "Ангелы"),
                Genre("Антигерой", "Антигерой"),
                Genre("Апокалиптический", "Апокалиптический"),
                Genre("Аристократия", "Аристократия"),
                Genre("Армия", "Армия"),
                Genre("Артефакты", "Артефакты"),
                Genre("Безумие", "Безумие"),
                Genre("Бейсбол", "Бейсбол"),
                Genre("Бог", "Бог"),
                Genre("Боги", "Боги"),
                Genre("Боевик", "Боевик"),
                Genre("Боевое", "Боевое"),
                Genre("Боевые искусства", "Боевые искусства"),
                Genre("Бои на мечах", "Бои на мечах"),
                Genre("Борьба за власть", "Борьба за власть"),
                Genre("Будущее", "Будущее"),
                Genre("Вампиры", "Вампиры"),
                Genre("Ведьма", "Ведьма"),
                Genre("Видеоигры", "Видеоигры"),
                Genre("Виртуальная реальность", "Виртуальная реальность"),
                Genre("Владыка демонов", "Владыка демонов"),
                Genre("Военный", "Военный"),
                Genre("Война", "Война"),
                Genre("Волшебник / Маг", "Волшебник / Маг"),
                Genre("Волшебные существа", "Волшебные существа"),
                Genre("Всесильный главный герой", "Всесильный главный герой"),
                Genre("Выживание", "Выживание"),
                Genre("Гарем", "Гарем"),
                Genre("Геймеры", "Геймеры"),
                Genre("Героическое фэнтези", "Героическое фэнтези"),
                Genre("Гильдии", "Гильдии"),
                Genre("Главная героиня", "Главная героиня"),
                Genre("Главный герой мужчина", "Главный герой мужчина"),
                Genre("Гоблины", "Гоблины"),
                Genre("Горничная", "Горничная"),
                Genre("Городское фэнтези", "Городское фэнтези"),
                Genre("Гурман", "Гурман"),
                Genre("Гэг-юмор", "Гэг-юмор"),
                Genre("Гяру", "Гяру"),
                Genre("Девочки-волшебницы", "Девочки-волшебницы"),
                Genre("Девушки-монстры", "Девушки-монстры"),
                Genre("Демоны", "Демоны"),
                Genre("Детектив", "Детектив"),
                Genre("Дзёсей", "Дзёсей"),
                Genre("Драконы", "Драконы"),
                Genre("драма", "драма"),
                Genre("Дружба", "Дружба"),
                Genre("Жестокий мир", "Жестокий мир"),
                Genre("Завоевание мира", "Завоевание мира"),
                Genre("Зверолюди", "Зверолюди"),
                Genre("Злодейка", "Злодейка"),
                Genre("Зомби", "Зомби"),
                Genre("Игровые элементы", "Игровые элементы"),
                Genre("Игры", "Игры"),
                Genre("Империи", "Империи"),
                Genre("Исекай", "Исекай"),
                Genre("Исторический", "Исторический"),
                Genre("История", "История"),
                Genre("Киберпанк", "Киберпанк"),
                Genre("комедия", "комедия"),
                Genre("Королевские дела", "Королевские дела"),
                Genre("Космос", "Космос"),
                Genre("Криминал / Преступники", "Криминал / Преступники"),
                Genre("Кровь", "Кровь"),
                Genre("Культивация", "Культивация"),
                Genre("Легендарное оружие", "Легендарное оружие"),
                Genre("Лоли", "Лоли"),
                Genre("Магическая академия", "Магическая академия"),
                Genre("Магические существа", "Магические существа"),
                Genre("Магия", "Магия"),
                Genre("Мафия", "Мафия"),
                Genre("Медицина", "Медицина"),
                Genre("Месть", "Месть"),
                Genre("Меха", "Меха"),
                Genre("Мистика", "Мистика"),
                Genre("Монстры", "Монстры"),
                Genre("Мурим", "Мурим"),
                Genre("Навыки", "Навыки"),
                Genre("Насилие", "Насилие"),
                Genre("Научная фантастика", "Научная фантастика"),
                Genre("Наёмники", "Наёмники"),
                Genre("Нежить", "Нежить"),
                Genre("Ниндзя", "Ниндзя"),
                Genre("Обмен телами", "Обмен телами"),
                Genre("Огнестрельное оружие", "Огнестрельное оружие"),
                Genre("Отношения", "Отношения"),
                Genre("Офисные работники", "Офисные работники"),
                Genre("Пародия", "Пародия"),
                Genre("Перерождение", "Перерождение"),
                Genre("Подземелье", "Подземелье"),
                Genre("Политика", "Политика"),
                Genre("Полиция", "Полиция"),
                Genre("Постапокалиптический", "Постапокалиптический"),
                Genre("Психологическое", "Психологическое"),
                Genre("Рабы", "Рабы"),
                Genre("Ранги силы", "Ранги силы"),
                Genre("Регрессия", "Регрессия"),
                Genre("Реинкарнация", "Реинкарнация"),
                Genre("Робот", "Робот"),
                Genre("романтика", "романтика"),
                Genre("Рыцари", "Рыцари"),
                Genre("Самурай", "Самурай"),
                Genre("Сверхъестественное", "Сверхъестественное"),
                Genre("Секс", "Секс"),
                Genre("Система", "Система"),
                Genre("Скрытие личности", "Скрытие личности"),
                Genre("Спасение мира", "Спасение мира"),
                Genre("Спорт", "Спорт"),
                Genre("Средневековье", "Средневековье"),
                Genre("Старшая школа", "Старшая школа"),
                Genre("Супер сила", "Супер сила"),
                Genre("Супергерои", "Супергерои"),
                Genre("Сэйнэн", "Сэйнэн"),
                Genre("Сянься", "Сянься"),
                Genre("Сёдзё", "Сёдзё"),
                Genre("Сёнэн", "Сёнэн"),
                Genre("Трагедия", "Трагедия"),
                Genre("Триллер", "Триллер"),
                Genre("Ужасы", "Ужасы"),
                Genre("Умный главный герой", "Умный главный герой"),
                Genre("Управление территорией", "Управление территорией"),
                Genre("Учитель", "Учитель"),
                Genre("Фантастика", "Фантастика"),
                Genre("Фехтование", "Фехтование"),
                Genre("Философия", "Философия"),
                Genre("Фэнтези", "Фэнтези"),
                Genre("Хоррор", "Хоррор"),
                Genre("Цундэрэ", "Цундэрэ"),
                Genre("Шантаж", "Шантаж"),
                Genre("Школа", "Школа"),
                Genre("Школьная жизнь", "Школьная жизнь"),
                Genre("Шоу-бизнес", "Шоу-бизнес"),
                Genre("Экшен", "Экшен"),
                Genre("Элементы юмора", "Элементы юмора"),
                Genre("Эльфы", "Эльфы"),
                Genre("Эротика", "Эротика"),
                Genre("этти", "этти"),
                Genre("Якудза", "Якудза"),
                Genre("Яндере", "Яндере"),
                Genre("Япония", "Япония"),
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
        private const val MEDIA_BASE = "https://cdn.tomilo-lib.ru"
        private val MEDIA_HOSTS = listOf(MEDIA_BASE, "https://tomilo-lib.ru")
        private const val MEDIA_STRIP = "/uploads"

        // Отключено: /_next/image — динамический оптимизатор Next.js, он режет обложки до 384px,
        // отдаёт 400 на чужие хосты (S3) и отваливается при пачке параллельных запросов.
        // Статический CDN отдаёт те же файлы оригинального размера без прокси.
        private const val MEDIA_PROXY = ""
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
