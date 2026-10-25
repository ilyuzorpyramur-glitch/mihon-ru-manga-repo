package eu.kanade.tachiyomi.extension.ru.mangalove

import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

/**
 * MangaLove (mangalove.ru) — сгенерировано extension-forge.
 *
 * Публичный REST API под /api/v1 с обязательным заголовком X-API-Key
 * (ключ зашит во фронтенд сайта). Качества страниц: data / data-saver.
 */
@Source
class MangaLove(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val baseUrlHost = baseUrl.toHttpUrl().host

    private val preferences by getPreferencesLazy()

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30.seconds)
        .readTimeout(60.seconds)
        .rateLimit(4, 1.seconds) { it.host == baseUrlHost }
        .addInterceptor { chain ->
            // API-ключ обязателен для всех запросов к /api/v1
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-API-Key", API_KEY)
                    .build(),
            )
        }
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mihon/1.0")
    }

    // ============================ Настройки качества ============================

    private val preferredQuality get() = preferences.getString(QUALITY_PREF, DEFAULT_QUALITY)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = "Качество изображений"
            entries = arrayOf("Полное качество", "Экономное (data-saver)")
            entryValues = arrayOf("data", "data-saver")
            summary = "%s"
            setDefaultValue(DEFAULT_QUALITY)
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(
                    screen.context,
                    "Для применения качества очистите кэш глав (Настройки → Дополнительно)",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.let(screen::addPreference)
    }

    // ============================== Каталог/поиск ==============================

    private fun catalogUrl(page: Int, sort: String, query: String? = null): HttpUrl = "$baseUrl/api/v1/manga/catalog".toHttpUrl().newBuilder()
        .addQueryParameter("lang", lang)
        .addQueryParameter("sort", sort)
        .addQueryParameter("page", page.toString())
        .addQueryParameter("per_page", "50")
        .apply {
            query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
        }
        .build()

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page, "rating"), headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<CatalogDto>()
        return MangasPage(
            data.manga.map { it.toSManga(baseUrl) },
            data.hasMore,
        )
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, "siteLatest"), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort = "popular"
        var status = ""

        var ageRating = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.selected
                is StatusFilter -> status = arrayOf("", "ongoing", "completed", "hiatus", "dropped")[filter.state]
                is ContentRatingFilter -> ageRating = filter.rating
                else -> {}
            }
        }

        val url = catalogUrl(page, sort, query).newBuilder()
        if (status.isNotBlank()) url.addQueryParameter("status", status)
        if (ageRating.isNotBlank()) url.addQueryParameter("age_rating", ageRating)
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Детали ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = response.parseAs<MangaDto>()
        return manga.toSManga(baseUrl).apply {
            status = when (manga.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Главы ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/api/v1/manga/${manga.url}/chapters?lang=$lang", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ChaptersDto>()
        return data.chapters.map { ch ->
            SChapter.create().apply {
                // url — относительный путь для pageListRequest
                val mangaId = response.request.url.pathSegments
                    .getOrNull(response.request.url.pathSegments.indexOf("manga") + 1)
                    ?: ch.id
                url = "/api/v1/manga/$mangaId/chapters/${ch.id}/pages"
                name = buildString {
                    if (ch.volume.isNotBlank() && ch.volume != "0") append("Том ${ch.volume} ")
                    append("Глава ${ch.chapterNum}")
                    if (ch.title.isNotBlank()) append(" — ${ch.title}")
                }
                chapter_number = ch.chapterNum.toFloatOrNull() ?: 0f
                scanlator = ch.scanlationGroup
                date_upload = ch.publishedAt?.let { parseDate(it) } ?: 0L
            }
        }
    }

    private fun parseDate(iso: String): Long = runCatching {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ============================== Страницы ==============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url + "?quality=$preferredQuality", headers)

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PagesDto>()
        return data.pages.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = // прокси-URL страниц отдаёт картинку без API-ключа
        GET(page.imageUrl!!, headers)

    companion object {
        private const val API_KEY =
            "a5e6bd2ad6b0b838a4d332757981f03a5639c937c4d92a9af9ddf7e330da94ad"
        private const val QUALITY_PREF = "preferred_quality"
        private const val DEFAULT_QUALITY = "data"
    }

    // ============================== Фильтры ==============================

    private class SortFilter :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("По популярности", "По рейтингу", "Последние обновления", "Новое на сайте"),
        ) {
        val selected get() = arrayOf("popular", "rating", "latest", "siteLatest")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Статус",
            arrayOf("Любой", "Выходит", "Завершено", "Перерыв", "Брошено"),
        )

    private class ContentRatingFilter :
        Filter.Select<String>(
            "Возрастной рейтинг",
            arrayOf("Любой", "16+", "18+"),
        ) {
        val rating get() = arrayOf("", "16", "18")[state]
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        ContentRatingFilter(),
    )
}
