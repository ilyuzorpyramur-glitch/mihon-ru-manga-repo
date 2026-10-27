package ru.remanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * Remanga (remanga.org) — сгенерировано extension-forge, доведено вручную.
 *
 * Каталог и контент — JSON API на api.remanga.org:
 * - /api/search/catalog/ — ленты (ordering, genres/categories/types/status/age_limit);
 * - /api/search/?query= — поиск по названию;
 * - /api/titles/{dir}/ — детали + ветки переводов;
 * - /api/titles/chapters/?branch_id= — главы (по 300 на страницу);
 * - /api/titles/chapters/{id}/ — страницы главы.
 * Проверить каталог и фильтры можно в самом приложении.
 * Защита сайта: DDoS-Guard — нужен десктопный User-Agent.
 */
@Source
class Remanga(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource() {
    override val supportsLatest = true

    private val api = "https://api.remanga.org"
    private val site = baseUrl

    private val randomizer = Random.nextInt().absoluteValue.toString()

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36 Edg/126.0.$randomizer",
        )
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Referer", "$site/")
    }

    private fun catalogUrl(
        ordering: String,
        page: Int,
        filters: FilterList?,
    ): HttpUrl {
        val builder = "$api/api/search/catalog/".toHttpUrl().newBuilder()
        builder.addQueryParameter("ordering", ordering)
        builder.addQueryParameter("count", PAGE_SIZE.toString())
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("count_chapters_gte", "1")
        if (filters != null) applyFilters(builder, filters)
        return builder.build()
    }

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl("-rating", page, null), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseCatalog(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl("-chapter_date", page, null), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response)

    private fun parseCatalog(response: Response): MangasPage {
        val catalog = response.parseAs<CatalogDto>(json)
        val items = catalog.content.map { it.toSManga() }
        val hasNext = catalog.props.total_pages?.let { catalog.props.page < it } ?: items.isNotEmpty()
        return MangasPage(items, hasNext)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        if (query.isNotBlank()) {
            val builder = "$api/api/search/".toHttpUrl().newBuilder()
            builder.addQueryParameter("query", query.trim())
            builder.addQueryParameter("page", page.toString())
            return GET(builder.build().toString(), headers)
        }
        val ordering = filters.filterIsInstance<OrderBy>().firstOrNull()?.ordering ?: "-rating"
        return GET(catalogUrl(ordering, page, filters), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val isSearch =
            response.request.url
                .toString()
                .contains("/api/search/?")
        if (isSearch) {
            val results = response.parseAs<List<LibraryDto>>(json)
            return MangasPage(results.map { it.toSManga() }, results.size >= 20)
        }
        return parseCatalog(response)
    }

    private fun applyFilters(
        builder: okhttp3.HttpUrl.Builder,
        filters: FilterList,
    ) {
        for (filter in filters) {
            when (filter) {
                is GenreGroup -> for (f in filter.state) {
                    if (f.isIncluded()) builder.addQueryParameter("genres", f.id)
                    if (f.isExcluded()) builder.addQueryParameter("exclude_genres", f.id)
                }
                is CategoryGroup -> for (f in filter.state) {
                    if (f.isIncluded()) builder.addQueryParameter("categories", f.id)
                    if (f.isExcluded()) builder.addQueryParameter("exclude_categories", f.id)
                }
                is TypeGroup -> for (f in filter.state) {
                    if (f.isIncluded()) builder.addQueryParameter("types", f.id)
                    if (f.isExcluded()) builder.addQueryParameter("exclude_types", f.id)
                }
                is StatusGroup -> for (f in filter.state) {
                    if (f.state) builder.addQueryParameter("status", f.id)
                }
                is AgeGroup -> for (f in filter.state) {
                    if (f.state) builder.addQueryParameter("age_limit", f.id)
                }
                else -> {}
            }
        }
    }

    // ================================ Детали ================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(api + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val wrapper = response.parseAs<DetailsWrapperDto>(json)
        return wrapper.content.toSManga()
    }

    private fun DetailsDto.toSManga(): SManga = SManga.create().apply {
        title = pickName(this@toSManga)
        url = "/api/titles/$dir/"
        thumbnail_url = site + img.high
        genre =
            buildList {
                if (type.name.isNotBlank()) add(type.name)
                when (age_limit) {
                    2 -> add("18+")
                    1 -> add("16+")
                }
                addAll((genres + categories).map { it.name })
            }.filter { it.isNotBlank() }.joinToString(", ")
        description = Jsoup.parse(this@toSManga.description.orEmpty()).text()
        status = parseStatus(this@toSManga.status.id)
        initialized = false
    }

    private fun pickName(dto: DetailsDto): String = if (dto.isEng == "rus") dto.rus_name else dto.en_name.ifBlank { dto.rus_name }

    private fun parseStatus(statusId: Int): Int = when (statusId) {
        0 -> SManga.COMPLETED
        1 -> SManga.ONGOING
        2 -> SManga.ON_HIATUS
        3 -> SManga.ON_HIATUS
        4 -> SManga.ONGOING
        5 -> SManga.LICENSED
        else -> SManga.UNKNOWN
    }

    // ================================ Главы ================================

    override fun chapterListRequest(manga: SManga): Request = GET(api + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val dir =
            response.request.url.encodedPath
                .substringAfter("/api/titles/")
                .substringBefore("/")
        val details = response.parseAs<DetailsWrapperDto>(json).content
        val branch = details.branches.maxByOrNull { it.count_chapters } ?: return emptyList()

        val chapters = ArrayList<ChapterDto>()
        var page = 1
        while (page <= MAX_CHAPTER_PAGES) {
            val request = GET("$api/api/titles/chapters/?branch_id=${branch.id}&page=$page&count=300", headers)
            val part = client.newCall(request).execute()
            if (!part.isSuccessful) {
                val code = part.code
                part.close()
                throw Exception("Не удалось получить главы (HTTP $code)")
            }
            val items = part.parseAs<ChaptersWrapperDto>(json).content
            part.close()
            if (items.isEmpty()) break
            chapters += items
            if (items.size < 300) break
            page += 1
        }

        return chapters
            .map { dto ->
                val number =
                    dto.chapter
                        .split(".")
                        .take(2)
                        .joinToString(".")
                        .toFloatOrNull() ?: -1f
                val volume = dto.tome
                var chapterName = "$volume. Глава ${dto.chapter}"
                if (dto.is_paid) chapterName += " \uD83D\uDCB2"
                if (dto.name.isNotBlank()) chapterName += " ${dto.name.trim()}"
                SChapter.create().apply {
                    url = "/manga/$dir/ch${dto.id}"
                    name = chapterName
                    chapter_number = number
                    date_upload = parseDate(dto.upload_date)
                    scanlator = dto.publishers.takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.name }
                }
            }.sortedWith(
                compareBy<SChapter> { it.name.substringBefore(". Глава").toIntOrNull() ?: 0 }
                    .thenBy { it.chapter_number },
            ).reversed()
    }

    // =============================== Страницы ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$api/api/titles/chapters/${chapter.url.substringAfterLast("/ch")}/", headers)

    override fun pageListParse(response: Response): List<Page> {
        val wrapper = response.parseAs<PagesWrapperDto>(json)
        return wrapper.content.pages
            .filter { it.height > MIN_PAGE_HEIGHT }
            .map { dto ->
                val link = if (dto.link.startsWith("http")) dto.link else site + dto.link
                Page(dto.page, imageUrl = link)
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ================================ Фильтры ================================

    override fun getFilterList(): FilterList = FilterList(
        OrderBy(),
        GenreGroup(GENRES),
        CategoryGroup(CATEGORIES),
        TypeGroup(TYPES),
        StatusGroup(),
        AgeGroup(),
    )

    @Serializable
    private data class ImgDto(
        val mid: String = "",
        val high: String = "",
    )

    @Serializable
    private data class LibraryDto(
        val name: String = "",
        val dir: String = "",
        val img: ImgDto = ImgDto(),
        val isEng: String? = null,
        val rus_name: String = "",
        val en_name: String = "",
    )

    @Serializable
    private data class CatalogPropsDto(
        val page: Int = 1,
        val total_pages: Int? = null,
    )

    @Serializable
    private data class CatalogDto(
        val content: List<LibraryDto> = emptyList(),
        val props: CatalogPropsDto = CatalogPropsDto(),
    )

    @Serializable
    private data class TagDto(
        val id: Int = 0,
        val name: String = "",
    )

    @Serializable
    private data class StatusDto(
        val id: Int = 0,
    )

    @Serializable
    private data class BranchDto(
        val id: Long = 0,
        val count_chapters: Int = 0,
    )

    @Serializable
    private data class DetailsDto(
        val id: Long = 0,
        val dir: String = "",
        val img: ImgDto = ImgDto(),
        val isEng: String? = null,
        val rus_name: String = "",
        val en_name: String = "",
        val another_name: String = "",
        val description: String? = null,
        val avg_rating: String = "0",
        val type: TagDto = TagDto(),
        val status: StatusDto = StatusDto(),
        val age_limit: Int = 0,
        val genres: List<TagDto> = emptyList(),
        val categories: List<TagDto> = emptyList(),
        val branches: List<BranchDto> = emptyList(),
    )

    @Serializable
    private data class DetailsWrapperDto(
        val content: DetailsDto = DetailsDto(),
    )

    @Serializable
    private data class PublisherDto(
        val name: String = "",
    )

    @Serializable
    private data class ChapterDto(
        val id: Long = 0,
        val chapter: String = "",
        val tome: Int = 0,
        val name: String = "",
        val upload_date: String = "",
        val publishers: List<PublisherDto> = emptyList(),
        val is_paid: Boolean = false,
    )

    @Serializable
    private data class ChaptersWrapperDto(
        val content: List<ChapterDto> = emptyList(),
    )

    @Serializable
    private data class PageItemDto(
        val page: Int = 0,
        val link: String = "",
        val height: Int = 0,
    )

    @Serializable
    private data class PagesDto(
        val pages: List<PageItemDto> = emptyList(),
    )

    @Serializable
    private data class PagesWrapperDto(
        val content: PagesDto = PagesDto(),
    )

    private fun LibraryDto.toSManga(): SManga {
        val manga = SManga.create()
        manga.title = if (isEng == "rus") rus_name else en_name.ifBlank { name }
        manga.url = "/api/titles/$dir/"
        manga.thumbnail_url = site + img.mid
        manga.initialized = false
        return manga
    }

    private fun parseDate(value: String): Long = runCatching { dateFormat.parse(value)?.time ?: 0L }.getOrDefault(0L)

    private val json =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    companion object {
        private const val PAGE_SIZE = 30
        private const val MAX_CHAPTER_PAGES = 20
        private const val MIN_PAGE_HEIGHT = 10
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
