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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import java.io.IOException
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

    private val branchesCache = mutableMapOf<String, List<BranchDto>>()

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

    private fun JsonObject?.str(key: String): String = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    private fun parseCatalog(response: Response): MangasPage {
        val root = response.parseAs<JsonElement>(json) as? JsonObject ?: return MangasPage(emptyList(), false)
        val items = root.arr("content").mapNotNull { el ->
            (el as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<LibraryDto>(it) }.getOrNull() }
        }
        val props = root["props"] as? JsonObject
        val page = (props?.get("page") as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        val total = (props?.get("total_pages") as? JsonPrimitive)?.content?.toIntOrNull()
        val hasNext = total?.let { page < it } ?: items.isNotEmpty()
        return MangasPage(items.map { it.toSManga() }, hasNext)
    }

    private fun JsonObject.arr(key: String): JsonArray = (get(key) as? JsonArray) ?: JsonArray(emptyList())

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
            val root = response.parseAs<JsonElement>(json)
            val arr = root as? JsonArray
                ?: (root as? JsonObject)?.arr("content")
                ?: JsonArray(emptyList())
            val results = arr.mapNotNull { el ->
                (el as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<LibraryDto>(it) }.getOrNull() }
            }
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
        val details = response.parseAs<JsonElement>(json).parseDetails()
        branchesCache[details.dir] = details.branches
        return details.toSManga()
    }

    // Закрытые по лицензии тайтлы отдают content массивом вместо объекта — нужен вход на сайте.
    private fun JsonElement.parseDetails(): DetailsDto {
        val content = (this as? JsonObject)?.get("content") as? JsonObject
            ?: throw IOException(AUTH_MSG)
        val safe = JsonObject(
            content.mapValues { (k, v) ->
                when (k) {
                    "img", "type", "status" -> if (v is JsonObject) v else JsonObject(emptyMap())
                    "genres", "categories", "branches" -> if (v is JsonArray) v else JsonArray(emptyList())
                    "description", "isEng", "rus_name", "en_name", "main_name", "secondary_name", "another_name" ->
                        if (v is JsonPrimitive && v.isString) v else JsonPrimitive("")
                    "avg_rating" -> (v as? JsonPrimitive)?.takeIf { it.isString } ?: JsonPrimitive("0")
                    "age_limit" -> (v as? JsonPrimitive)?.takeIf { !it.isString } ?: JsonPrimitive(0)
                    else -> v
                }
            },
        )
        return json.decodeFromJsonElement<DetailsDto>(safe)
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

    private fun pickName(dto: DetailsDto): String = if (dto.isEng == "rus") dto.rus_name.ifBlank { dto.main_name } else dto.en_name.ifBlank { dto.secondary_name.ifBlank { dto.rus_name } }

    private fun parseStatus(statusId: Int): Int = when (statusId) {
        0 -> SManga.COMPLETED
        1 -> SManga.ONGOING
        2 -> SManga.ON_HIATUS
        3 -> SManga.ON_HIATUS
        4 -> SManga.ONGOING
        5 -> SManga.LICENSED
        6 -> SManga.LICENSED
        else -> SManga.UNKNOWN
    }

    // ================================ Главы ================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.defer {
        val dir = manga.url.substringAfter("/api/titles/").substringBefore("/")
        val branch = resolveBranch(dir, manga)
        if (branch == null) {
            Observable.just(emptyList<SChapter>())
        } else {
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
                val chRoot = part.parseAs<JsonElement>(json) as? JsonObject
            val items = chRoot.arr("content").mapNotNull { el ->
                (el as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<ChapterDto>(it) }.getOrNull() }
            }
                part.close()
                if (items.isEmpty()) break
                chapters += items
                if (items.size < 300) break
                page += 1
            }
            val list = chapters
                .map { dto -> dto.toSChapter(dir) }
                .filterPaidTail()
                .sortedWith(
                    compareBy<SChapter> { it.name.substringBefore(". Глава").toIntOrNull() ?: 0 }
                        .thenBy { it.chapter_number },
                ).reversed()
            Observable.just(list)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // Ветки берём из кэша, заполненного при разборе деталей, — повторный запрос деталей лишний.
    private fun resolveBranch(
        dir: String,
        manga: SManga,
    ): BranchDto? {
        val cached = branchesCache[dir]
        if (cached != null) return cached.maxByOrNull { it.count_chapters }
        val details = client.newCall(GET(api + manga.url, headers)).execute().parseAs<JsonElement>(json).parseDetails()
        branchesCache[details.dir] = details.branches
        return details.branches.maxByOrNull { it.count_chapters }
    }

    // Платные главы новее последней бесплатной ещё не вышли бесплатно — скрываем их хвост.
    private fun List<SChapter>.filterPaidTail(): List<SChapter> {
        val free = filter { !it.name.contains(PAID_MARK) }
        if (free.isEmpty()) return this
        val maxFreeVol = free.maxOf { it.name.substringBefore(". Глава").toIntOrNull() ?: 0 }
        val maxFreeNum = free
            .filter { (it.name.substringBefore(". Глава").toIntOrNull() ?: 0) == maxFreeVol }
            .maxOf { it.chapter_number }
        return filterNot { ch ->
            val vol = ch.name.substringBefore(". Глава").toIntOrNull() ?: 0
            ch.name.contains(PAID_MARK) && (vol > maxFreeVol || (vol == maxFreeVol && ch.chapter_number > maxFreeNum))
        }
    }

    private fun ChapterDto.toSChapter(dir: String): SChapter {
        val dto = this
        val chapterNumber = dto.chapter.split(".").take(2).joinToString(".").toFloatOrNull() ?: -1f
        var chapterTitle = "${dto.tome}. Глава ${dto.chapter}"
        if (dto.is_paid) chapterTitle += PAID_MARK
        if (dto.name.isNotBlank()) chapterTitle += " ${dto.name.trim()}"
        return SChapter.create().apply {
            url = "/manga/$dir/ch${dto.id}"
            this.name = chapterTitle
            chapter_number = chapterNumber
            date_upload = parseDate(dto.upload_date)
            scanlator = dto.publishers.takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.name }
        }
    }

    // =============================== Страницы ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$api/api/titles/chapters/${chapter.url.substringAfterLast("/ch")}/", headers)

    override fun pageListParse(response: Response): List<Page> {
        val root = response.parseAs<JsonElement>(json) as? JsonObject
        val msg = root.str("msg")
        val content = root?.get("content") as? JsonObject
        if (content == null) {
            throw IOException(msg.ifBlank { "Глава недоступна (платная или удалена)" })
        }
        val pages = (content["pages"] as? JsonArray) ?: JsonArray(emptyList())
        val parsed = pages.mapNotNull { el ->
            (el as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<PageItemDto>(it) }.getOrNull() }
        }
        if (parsed.isEmpty()) {
            throw IOException(msg.ifBlank { "Страницы не найдены" })
        }
        return parsed
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
        val main_name: String = "",
        val secondary_name: String = "",
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
        val main_name: String = "",
        val secondary_name: String = "",
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
        val msg: String? = null,
    )

    private fun LibraryDto.toSManga(): SManga {
        val manga = SManga.create()
        manga.title = if (isEng == "rus") rus_name.ifBlank { main_name } else en_name.ifBlank { secondary_name.ifBlank { name } }
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
        private const val PAID_MARK = " \uD83D\uDCB2"
        private const val AUTH_MSG = "Для просмотра контента необходима авторизация через WebView \uD83C\uDF0E"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
