package ru.animestock

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * AnimeStock (animestock.ru) — сгенерировано extension-forge, доведено вручную.
 *
 * Сайт на конструкторе Craftum: каталог статей отдаёт CMS API
 * /user-website-api/blog/rubrics/{id}/articles/ (limit/offset, tags, published).
 * Каждый пост = один выпуск: одна длинная полоса (long strip) — img.cli-article__image.
 * Поискового API у сайта нет — поиск делается в памяти по загруженному списку.
 * Фильтры: рубрика (Манга/Хентай/Косплей) и жанровые теги сайта.
 */
@Source
class AnimeStock(
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

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .connectTimeout(30.seconds)
            .readTimeout(60.seconds)
            .rateLimit(2, 1.seconds)
            .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", UA)
        add("Referer", "$baseUrl/")
        add("Accept-Language", "ru,en;q=0.8")
    }

    // =============================== Каталог ===============================

    private fun articlesUrl(
        rubricId: Long,
        page: Int,
        tagId: Long? = null,
        limit: Int = PAGE_SIZE + 1,
    ): HttpUrl = "$baseUrl$API_PATH/rubrics/$rubricId/articles/".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
        .addQueryParameter("deleted", "false")
        .addQueryParameter("published", "true")
        .apply { tagId?.let { addQueryParameter("tags", it.toString()) } }
        .build()

    override fun popularMangaRequest(page: Int): Request = GET(articlesUrl(RUBRIC_MANGA, page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseArticles(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseArticles(response)

    private fun parseArticles(response: Response): MangasPage {
        val articles = response.parseAs<List<ArticleDto>>(json)
        val hasNext = articles.size > PAGE_SIZE
        return MangasPage(articles.take(PAGE_SIZE).map { it.toSManga() }, hasNext)
    }

    // ================================ Поиск ================================
    // Поискового API нет: грузим списки рубрик целиком и фильтруем в памяти.
    // Страница и параметры переносятся в query самого запроса.

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val rubric = filters.filterIsInstance<RubricFilter>().firstOrNull()?.selectedRubric
            ?: RUBRIC_MANGA
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedTag
        val rubrics = if (rubric == RUBRIC_ALL) SEARCH_RUBRICS.toList() else listOf(rubric)

        val first = rubrics.first()
        val url = articlesUrl(first, page = 1, tagId = tag?.let { SITE_TAGS[it]?.get(first) }, limit = SEARCH_LIMIT)
            .newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("q", query)
            .addQueryParameter("tag", tag.orEmpty())
            .addQueryParameter("rubrics", rubrics.joinToString(","))
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = url.queryParameter("q").orEmpty().trim()
        val tag = url.queryParameter("tag").orEmpty().ifBlank { null }
        val rubrics = url.queryParameter("rubrics")?.split(",")?.mapNotNull(String::toLongOrNull)
            ?: listOf(RUBRIC_MANGA)

        // Первый запрос уже выполнен: используем его ответ, если тег применим к этой рубрике
        val firstTagOk = tag == null || SITE_TAGS[tag]?.containsKey(rubrics.first()) == true
        val collected = if (firstTagOk) response.parseAs<List<ArticleDto>>(json).toMutableList() else mutableListOf()

        for (rubric in rubrics.drop(1)) {
            val tagId = tag?.let { SITE_TAGS[it]?.get(rubric) } ?: if (tag == null) null else continue
            val sub = client.newCall(GET(articlesUrl(rubric, page = 1, tagId = tagId, limit = SEARCH_LIMIT).toString(), headers))
                .execute()
            sub.use { collected += it.parseAs<List<ArticleDto>>(json) }
        }

        val filtered = collected
            .distinctBy { it.id }
            .filter { it.url.isNotBlank() && it.name.isNotBlank() }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .sortedByDescending { it.first_published_at }

        val from = (page - 1) * PAGE_SIZE
        val chunk = filtered.drop(from).take(PAGE_SIZE)
        return MangasPage(chunk.map { it.toSManga() }, from + PAGE_SIZE < filtered.size)
    }

    // ================================ Детали ================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.title().substringBefore(" | ").trim()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content").orEmpty()
        description = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        status = SManga.UNKNOWN
        initialized = false
    }

    // ================================ Главы ================================
    // Пост = один выпуск-полоса, поэтому у записи одна глава.

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val title = document.title().substringBefore(" | ").trim()
        val number = Regex("""#(\d+(?:[.,]\d+)?)""").find(title)
            ?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: 1f

        return listOf(
            SChapter.create().apply {
                url = response.request.url.encodedPath
                name = "Полоса"
                chapter_number = number
                date_upload = parsePublished(
                    document.selectFirst("meta[property=article:published_time], time[datetime]")
                        ?.attr("content")
                        ?.takeIf { it.isNotEmpty() }
                        ?: document.selectFirst("time[datetime]")?.attr("datetime"),
                )
            },
        )
    }

    private fun parsePublished(value: String?): Long {
        if (value.isNullOrEmpty()) return 0L
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(value.take(19))
                    .atZone(ZoneId.of("Europe/Moscow"))
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrDefault(0L)
    }

    // =============================== Страницы ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select(PAGE_IMAGE_SELECTOR)
            .map { it.attr("abs:src") }
            .filter { it.startsWith("http") }
            .distinct()
        if (images.isNotEmpty()) return images.mapIndexed { index, url -> Page(index, imageUrl = url) }

        document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.let { cover ->
            return listOf(Page(0, imageUrl = cover))
        }
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ================================ Фильтры ================================

    override fun getFilterList() = FilterList(RubricFilter(), TagFilter())

    private fun ArticleDto.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.url
        title = name.trim()
        thumbnail_url = preview_url.takeIf { it.startsWith("http") }
        initialized = false
    }

    @Serializable
    private data class RubricDto(val id: Long = 0, val name: String = "")

    @Serializable
    private data class ArticleDto(
        val id: Long = 0,
        val name: String = "",
        val url: String = "",
        val preview_url: String = "",
        val first_published_at: String = "",
        val rubric: RubricDto? = null,
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        private const val API_PATH = "/user-website-api/blog"
        private const val PAGE_SIZE = 20
        private const val SEARCH_LIMIT = 500
        private const val PAGE_IMAGE_SELECTOR = "img.cli-article__image, .cr-article__content img, .cr-article img"
    }
}
