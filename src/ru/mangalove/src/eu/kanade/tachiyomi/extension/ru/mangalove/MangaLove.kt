package ru.mangalove

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
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * MangaLove (mangalove.ru) — сгенерировано extension-forge.
 *
 * API-ключ X-API-Key (из фронтенда). Сортировки: popular, rating, latest, siteLatest. Главы ≤100/страница, каталог ≤50/страницу. Поля-числа приходят то строкой, то числом.
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences by getPreferencesLazy()

    private val baseUrlHost = baseUrl.toHttpUrl().host

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30.seconds)
        .readTimeout(60.seconds)
        .rateLimit(4, 1.seconds) { it.host == baseUrlHost }
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(API_KEY_HEADER, API_KEY)
                    .build(),
            )
        }
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) MangaLove/1.0")
    }

    // ================================ Качество ================================

    private val preferredQuality
        get() = preferences.getString(QUALITY_PREF, DEFAULT_QUALITY) ?: DEFAULT_QUALITY

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = "Качество изображений"
            entries = QUALITY_LABELS
            entryValues = QUALITY_IDS
            summary = "%s"
            setDefaultValue(DEFAULT_QUALITY)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Для применения очистите кэш глав (Настройки → Дополнительно)",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.let(screen::addPreference)
    }

    // ============================== Каталог/поиск ==============================

    private fun catalogUrl(page: Int, sort: String, query: String? = null): HttpUrl = "$baseUrl$CATALOG_PATH".toHttpUrl().newBuilder()
        .addQueryParameter("lang", lang)
        .addQueryParameter("sort", sort)
        .addQueryParameter("page", page.toString())
        .addQueryParameter("per_page", PER_PAGE.toString())
        .apply {
            query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
        }
        .build()

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page, DEFAULT_SORT), headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<CatalogDto>(json)
        return MangasPage(data.manga.map { it.toSManga() }, data.hasMore)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, LATEST_SORT), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort = DEFAULT_SORT
        var status = ""
        var ageRating = ""

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.selected
                is StatusFilter -> status = filter.selected
                is AgeRatingFilter -> ageRating = filter.selected
                else -> {}
            }
        }

        val url = catalogUrl(page, sort, query).newBuilder()
        if (status.isNotEmpty()) url.addQueryParameter("status", status)
        if (ageRating.isNotEmpty()) url.addQueryParameter("age_rating", ageRating)
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ================================ Детали ================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + DETAILS_PATH.replace("{id}", manga.url), headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<MangaDto>(json).toSManga()

    // ================================= Главы =================================

    override fun chapterListRequest(manga: SManga): Request = chapterPageRequest(manga.url, 1)

    private fun chapterPageRequest(mangaId: String, page: Int): Request = GET(
        baseUrl + CHAPTERS_PATH.replace("{id}", mangaId) +
            "?lang=" + lang + "&page=" + page,
        headers,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val segments = response.request.url.pathSegments
        val mangaId = segments.getOrNull(segments.indexOf("manga") + 1) ?: return emptyList()

        var data = response.parseAs<ChaptersDto>(json)
        val chapters = data.chapters.toMutableList()
        var page = 1

        while (chapters.isNotEmpty() && chapters.size < data.total && page < MAX_CHAPTER_PAGES) {
            page++
            data = client.newCall(chapterPageRequest(mangaId, page)).execute().parseAs(json)
            if (data.chapters.isEmpty()) break
            chapters += data.chapters
        }

        return chapters.distinctBy { it.id }.map { it.toSChapter(mangaId) }
    }

    private fun ChapterDto.toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        url = PAGES_PATH
            .replace("{id}", mangaId)
            .replace("{chapter}", id)
        name = buildString {
            if (volume.isNotEmpty() && volume != "0") append("Том $volume ")
            append("Глава $chapterNum")
            if (title.isNotEmpty()) append(" — $title")
        }
        chapter_number = chapterNum.toFloatOrNull() ?: -1f
        scanlator = scanlationGroup.takeIf { it.isNotEmpty() }
        date_upload = publishedAt.toEpochMillis()
    }

    private fun String.toEpochMillis(): Long = runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ================================ Страницы ================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}?quality=$preferredQuality", headers)

    override fun pageListParse(response: Response): List<Page> = response.parseAs<PagesDto>(json).pages.mapIndexed { index, url ->
        Page(index, imageUrl = url)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // ================================ Маппинг ================================

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = id
        title = titleRu.ifBlank { title }
        thumbnail_url = coverUrl.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("http")) it else baseUrl + it
        }
        author = this@toSManga.author.takeIf { it.isNotEmpty() }
        artist = this@toSManga.artist.takeIf { it.isNotEmpty() }
        description = this@toSManga.descriptionRu.ifBlank { this@toSManga.description }
        genre = (genres + themes).distinct().joinToString(", ").takeIf { it.isNotEmpty() }
        status = this@toSManga.status.toSMangaStatus()
        initialized = false
    }

    private fun String.toSMangaStatus(): Int = when (this) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ================================ Фильтры ================================

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
        ) {
        val selected get() = arrayOf("", "ongoing", "completed", "hiatus", "dropped")[state]
    }

    private class AgeRatingFilter :
        Filter.Select<String>(
            "Возрастной рейтинг",
            arrayOf("Любой", "16+", "18+"),
        ) {
        val selected get() = arrayOf("", "16", "18")[state]
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        AgeRatingFilter(),
    )

    companion object {
        private const val API_KEY_HEADER = "X-API-Key"
        private const val API_KEY = "a5e6bd2ad6b0b838a4d332757981f03a5639c937c4d92a9af9ddf7e330da94ad"
        private const val QUALITY_PREF = "preferred_quality"
        private const val DEFAULT_QUALITY = "data"
        private const val CATALOG_PATH = "/api/v1/manga/catalog"
        private const val DETAILS_PATH = "/api/v1/manga/{id}"
        private const val CHAPTERS_PATH = "/api/v1/manga/{id}/chapters"
        private const val PAGES_PATH = "/api/v1/manga/{id}/chapters/{chapter}/pages"
        private const val PER_PAGE = 50
        private const val MAX_CHAPTER_PAGES = 20
        private const val DEFAULT_SORT = "popular"
        private const val LATEST_SORT = "siteLatest"
        private val QUALITY_IDS = arrayOf("data", "data-saver")
        private val QUALITY_LABELS = arrayOf("Полное качество", "Экономное (data-saver)")
    }
}
