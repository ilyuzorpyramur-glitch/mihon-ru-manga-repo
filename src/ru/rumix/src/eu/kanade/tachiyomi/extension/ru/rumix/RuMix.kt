package eu.kanade.tachiyomi.extension.ru.rumix

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class RuMix : KeiSource() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    // ───────────────────────────── Каталог ─────────────────────────────

    private fun catalogUrl(
        page: Int,
        query: String,
        sort: String,
        filters: FilterList?,
    ): HttpUrl {
        val builder =
            "$baseUrl/api/catalog/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("sortType", sort)

        filters?.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    builder.setQueryParameter("sortType", filter.selected())
                }

                is GenreGroup -> {
                    filter.state.forEach { genre ->
                        when (genre.state) {
                            Filter.TriState.STATE_INCLUDE -> {
                                builder.addQueryParameter("includeElementIds", genre.id)
                            }

                            Filter.TriState.STATE_EXCLUDE -> {
                                builder.addQueryParameter("excludeElementIds", genre.id)
                            }
                        }
                    }
                }

                is YearGroup -> {
                    val from =
                        filter.state
                            .firstInstanceOrNull<YearFrom>()
                            ?.state
                            ?.trim()
                            .orEmpty()
                    val to =
                        filter.state
                            .firstInstanceOrNull<YearTo>()
                            ?.state
                            ?.trim()
                            .orEmpty()
                    if (from.isNotEmpty() || to.isNotEmpty()) {
                        builder.addQueryParameter("years", "${from.ifEmpty { "1900" }},${to.ifEmpty { "2100" }}")
                    }
                }

                else -> {}
            }
        }

        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())

        return builder.build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = searchCatalog(page, "", SORT_RATING, null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = searchCatalog(page, "", SORT_DATE_UPDATE, null)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected() ?: SORT_RATING
        return searchCatalog(page, query, sort, filters)
    }

    private suspend fun searchCatalog(
        page: Int,
        query: String,
        sort: String,
        filters: FilterList?,
    ): MangasPage {
        val response = client.get(catalogUrl(page, query, sort, filters)).parseAs<CatalogResponse>()
        val mangas = response.list.mapNotNull { it.toSManga() }
        return MangasPage(mangas, response.offset + mangas.size < response.total)
    }

    private fun CatalogItem.toSManga(): SManga? {
        val slug = elementId?.linkName?.takeIf { it.isNotBlank() } ?: return null
        return SManga.create().apply {
            url = "/$slug"
            title = name
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

        // Основное название лежит в meta[itemprop=name]; h1.names содержит ещё и алиасы,
        // а .item-title — это ячейка таблицы глав, а не заголовок.
        val rawTitle = document.selectFirst("meta[itemprop=name]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.names span.name")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: manga.title

        title = rawTitle

        thumbnail_url = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img[data-pic-url]")?.let {
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
                document.selectFirst(".elem_status")?.text()?.contains("Заверш", true) == true -> SManga.COMPLETED
                document.selectFirst(".elem_status")?.text()?.contains("Онгоинг", true) == true -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
    }

    private fun parseChapters(
        document: Document,
        mangaUrl: String,
    ): List<SChapter> = document
        .select("a[href*=\"/vol\"]")
        .mapNotNull { it.toChapter(mangaUrl) }
        .distinctBy { it.url }

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
        SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).parse(raw)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    // ───────────────────────────── Страницы ─────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()

        // Страницы ридера RuMix собирает JavaScript: в HTML лежат только обложки томов.
        return document
            .select("img[data-page]")
            .mapNotNull { element ->
                element
                    .absUrl("data-original")
                    .ifBlank { element.absUrl("data-pic-url") }
                    .ifBlank { element.absUrl("src") }
                    .takeIf { it.isNotBlank() }
            }.distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // ───────────────────────────── Фильтры ─────────────────────────────

    override val supportsFilterFetching: Boolean = false

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        SortFilter(),
        GenreGroup(),
        YearGroup(),
    )

    class SortFilter :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("По рейтингу", "По популярности", "По дате обновления", "По дате добавления"),
        ) {
        fun selected(): String = when (state) {
            1 -> SORT_POPULARITY
            2 -> SORT_DATE_UPDATE
            3 -> SORT_DATE_CREATE
            else -> SORT_RATING
        }
    }

    class GenreFilter(
        name: String,
        val id: String,
    ) : Filter.TriState(name)

    class GenreGroup : Filter.Group<GenreFilter>("Жанры", GENRES.map { GenreFilter(it.first, it.second) })

    class YearFrom : Filter.Text("От")

    class YearTo : Filter.Text("До")

    class YearGroup : Filter.Group<Filter<String>>("Год выпуска", listOf(YearFrom(), YearTo()))

    companion object {
        private const val PAGE_SIZE = 50
        private const val SORT_RATING = "RATING"
        private const val SORT_POPULARITY = "POPULARITY"
        private const val SORT_DATE_UPDATE = "DATE_UPDATE"
        private const val SORT_DATE_CREATE = "DATE_CREATE"
        private val CHAPTER_REGEX = Regex("/vol(\\d+)/(\\d+)")
        private val PAGE_URL_REGEX = Regex("https://static\\.rumix\\.me/uploads/pics/[^\"'\\\\\\s<>]+\\.(?:jpg|jpeg|png|webp)")
        private val COVER_REGEX = Regex("_p\\.(?:jpg|jpeg|png)$")

        // Жанры и их идентификаторы взяты с /search/advanced (проверено: includeElementIds фильтрует).
        private val GENRES =
            listOf(
                "арт" to "5685",
                "боевик" to "2155",
                "боевые искусства" to "2143",
                "вампиры" to "2148",
                "Вестерн" to "9150",
                "гарем" to "2142",
                "гендерная интрига" to "2156",
                "героическое фэнтези" to "2146",
                "детектив" to "2152",
                "дзёсэй" to "2158",
                "додзинси" to "2141",
                "драма" to "2118",
                "игра" to "2154",
                "история" to "2119",
                "киберпанк" to "8032",
                "кодомо" to "2137",
                "комедия" to "2136",
                "махо-сёдзё" to "2147",
                "меха" to "2126",
                "мистика" to "9151",
                "научная фантастика" to "2133",
                "повседневность" to "2135",
                "постапокалиптика" to "2151",
                "приключения" to "2130",
                "психология" to "2144",
                "романтика" to "2121",
                "самурайский боевик" to "2124",
                "сверхъестественное" to "2159",
                "сёдзё" to "2122",
                "сёнэн" to "2134",
                "спорт" to "2129",
                "сэйнэн" to "2138",
                "трагедия" to "2153",
                "триллер" to "2150",
                "ужасы" to "2125",
                "Фантастика" to "9153",
                "фэнтези" to "2131",
                "школа" to "2127",
                "этти" to "2149",
                "BD" to "9142",
                "В цвете" to "7290",
                "Веб" to "2160",
                "Ёнкома" to "2161",
                "Комикс" to "3515",
                "Манхва" to "3001",
                "Маньхуа" to "3002",
                "Ранобэ" to "8575",
                "Сборник" to "2157",
                "0+" to "9154",
                "12+" to "9156",
                "16+" to "9139",
                "18+, запрещено для детей" to "9145",
                "6+" to "9155",
                "PG" to "6179",
                "PG-13" to "6181",
            )
    }
}
