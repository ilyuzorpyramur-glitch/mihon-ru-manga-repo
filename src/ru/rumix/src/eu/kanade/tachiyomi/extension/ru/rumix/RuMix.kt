package eu.kanade.tachiyomi.extension.ru.rumix

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

@Source
abstract class RuMix :
    KeiSource(),
    ConfigurableSource {
    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    private fun defaultSort(): String = preferences.getString(PREF_SORT, SORT_RATING) ?: SORT_RATING

    // ───────────────────────────── Каталог ─────────────────────────────

    private fun catalogUrl(
        page: Int,
        query: String,
        sort: String,
    ) = "$baseUrl/api/catalog/search"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .addQueryParameter("sortType", sort)
        .apply { if (query.isNotBlank()) addQueryParameter("q", query.trim()) }
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = searchCatalog(page, "", defaultSort())

    override suspend fun getLatestUpdates(page: Int): MangasPage = searchCatalog(page, "", SORT_DATE_UPDATE)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected() ?: defaultSort()
        return searchCatalog(page, query, sort)
    }

    private suspend fun searchCatalog(
        page: Int,
        query: String,
        sort: String,
    ): MangasPage {
        val response = client.get(catalogUrl(page, query, sort)).parseAs<CatalogResponse>()
        val mangas = response.list.mapNotNull { it.toSManga() }
        return MangasPage(mangas, response.offset + mangas.size < response.total)
    }

    private fun CatalogItem.toSManga(): SManga? {
        val slug = elementId?.linkName?.takeIf { it.isNotBlank() } ?: return null
        return SManga.create().apply {
            url = "/$slug"
            title = this@toSManga.name
            thumbnail_url = picUrl
        }
    }

    // ───────────────────────────── Тайтл ─────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()

        val details = parseDetails(document, manga)
        val chapterList = if (fetchChapters) parseChapters(document, manga.url) else chapters

        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(
        document: Document,
        manga: SManga,
    ): SManga = SManga.create().apply {
        url = manga.url

        val rawTitle =
            document.selectFirst(".item-title")?.text()
                ?: document.selectFirst("h1")?.text().orEmpty()

        title =
            rawTitle
                .substringBefore("Другие имена")
                .removePrefix("Манга")
                .removePrefix("Манхва")
                .removePrefix("Маньхуа")
                .trim()
                .ifBlank { manga.title }

        thumbnail_url = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img[data-pic-url], .item-poster img, .poster img")?.let {
                it.absUrl("data-pic-url").ifBlank { it.absUrl("src") }
            }
            ?: manga.thumbnail_url

        author =
            document
                .select("a[href*=\"/people/\"]")
                .eachText()
                .firstOrNull()
                ?.trim()
        genre =
            document
                .select(".elem_genre")
                .eachText()
                .map { it.trim().removePrefix("#") }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()

        description =
            document
                .selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                .orEmpty()
        status =
            when {
                document.text().contains("Завершён", ignoreCase = true) -> SManga.COMPLETED
                document.text().contains("Онгоинг", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
    }

    private fun parseChapters(
        document: Document,
        mangaUrl: String,
    ): List<SChapter> = document
        .select("a[href*=/vol]")
        .mapNotNull { it.toChapter(mangaUrl) }
        .distinctBy { it.url }
        .reversed()

    private fun Element.toChapter(mangaUrl: String): SChapter? {
        val href = absUrl("href").ifBlank { attr("href") }
        val match = CHAPTER_REGEX.find(href) ?: return null

        return SChapter.create().apply {
            url = "/${mangaUrl.trim('/')}/vol${match.groupValues[1]}/${match.groupValues[2]}"
            name = text().trim().ifBlank { "Глава ${match.groupValues[2]}" }
            chapter_number = match.groupValues[2].toFloatOrNull() ?: -1f
            date_upload = attr("data-date").takeIf { it.isNotBlank() }?.let(::parseDate) ?: 0L
        }
    }

    private fun parseDate(raw: String): Long = try {
        java.text
            .SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)
            .parse(raw)
            ?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    // ───────────────────────────── Страницы ─────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()

        val images =
            document
                .select("img[data-pic-url], img[data-original]")
                .map { element ->
                    element.absUrl("data-pic-url").ifBlank {
                        element.absUrl("data-original").ifBlank { element.absUrl("src") }
                    }
                }.filter { it.isNotBlank() && it.contains("/pics/") }
                .distinct()

        return images.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // ───────────────────────────── Фильтры ─────────────────────────────

    override val supportsFilterFetching: Boolean = false

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        SortFilter(),
    )

    open class SortFilter :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("По рейтингу", "По популярности", "По дате обновления"),
        ) {
        fun selected(): String = when (state) {
            1 -> SORT_POPULARITY
            2 -> SORT_DATE_UPDATE
            else -> SORT_RATING
        }
    }

    // ───────────────────────────── Настройки ─────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_SORT
                title = "Сортировка по умолчанию"
                entries = arrayOf("По рейтингу", "По популярности", "По дате обновления")
                entryValues = arrayOf(SORT_RATING, SORT_POPULARITY, SORT_DATE_UPDATE)
                setDefaultValue(SORT_RATING)
                summary = "%s"
            }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val PREF_SORT = "pref_sort"
        private const val SORT_RATING = "RATING"
        private const val SORT_POPULARITY = "POPULARITY"
        private const val SORT_DATE_UPDATE = "DATE_UPDATE"
        private val CHAPTER_REGEX = Regex("""/vol(\d+)/(\d+)""")
    }
}
