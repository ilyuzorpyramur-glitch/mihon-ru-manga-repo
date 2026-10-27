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
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * AnimeStock (animestock.ru) — сгенерировано extension-forge, доведено вручную.
 *
 * Сайт на конструкторе Craftum: каталог статей отдаёт CMS API
 * /user-website-api/blog/rubrics/{id}/articles/ (limit/offset, tags, published).
 * Сортировок у API нет, поэтому «Популярное» — рубрика «Манга»,
 * а «Последнее» — объединённая лента «Манга» + «Хентай» по дате.
 * Каждый пост = один выпуск: одна длинная полоса (long strip) — img.cli-article__image.
 * Поискового API у сайта нет — поиск делается в памяти по загруженному списку.
 * Страницы-подборки (альбомы моделей/персонажей) содержат собственную рубрику
 * (data-heading-id): её посты становятся главами — так доступны все фото альбома.
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

    private var sitemapCache: List<Pair<String, Long>>? = null

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

    private fun articlesUrl(rubricId: Long, page: Int, tagId: Long? = null, limit: Int = PAGE_SIZE + 1): HttpUrl {
        val builder = "$baseUrl$API_PATH/rubrics/$rubricId/articles/".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("deleted", "false")
            .addQueryParameter("published", "true")
        tagId?.let { builder.addQueryParameter("tags", it.toString()) }
        return builder.build()
    }

    private fun fetchArticles(rubricId: Long, page: Int, limit: Int = PAGE_SIZE + 1): List<ArticleDto> {
        val request = GET(articlesUrl(rubricId, page, limit = limit).toString(), headers)
        return client.newCall(request).execute().use { response ->
            response.parseAs<List<ArticleDto>>(json)
        }
    }

    override fun popularMangaRequest(page: Int): Request = GET(articlesUrl(RUBRIC_MANGA, page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseArticles(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(articlesUrl(RUBRIC_MANGA, page), headers)

    // «Последнее» — вся лента сайта: Манга + Хентай, слитно по дате публикации
    override fun latestUpdatesParse(response: Response): MangasPage {
        val offset = response.request.url.queryParameter("offset")?.toIntOrNull() ?: 0
        val page = offset / PAGE_SIZE + 1
        val manga = fetchArticles(RUBRIC_MANGA, page)
        val hentai = runCatching { fetchArticles(RUBRIC_HENTAI, page) }.getOrDefault(emptyList())
        val merged = (manga + hentai)
            .distinctBy { it.id }
            .sortedByDescending { it.first_published_at }
        val hasNext = manga.size > PAGE_SIZE || hentai.size > PAGE_SIZE
        return MangasPage(merged.take(PAGE_SIZE).map { it.toSManga() }, hasNext)
    }

    private fun parseArticles(response: Response): MangasPage {
        val articles = response.parseAs<List<ArticleDto>>(json)
        val hasNext = articles.size > PAGE_SIZE
        return MangasPage(articles.take(PAGE_SIZE).map { it.toSManga() }, hasNext)
    }

    // ================================ Поиск ================================
    // Поискового API нет: грузим списки рубрик целиком и фильтруем в памяти.
    // Страница и параметры переносятся в query самого запроса.

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val rubric = filters.filterIsInstance<RubricFilter>().firstOrNull()?.selectedRubric ?: RUBRIC_MANGA
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedTag
        val rubrics = if (rubric == RUBRIC_ALL) SEARCH_RUBRICS.toList() else listOf(rubric)

        val first = rubrics.first()
        val base = articlesUrl(first, page = 1, tagId = tag?.let { SITE_TAGS[it]?.get(first) }, limit = SEARCH_LIMIT)
        val url = base.newBuilder()
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
    // Пост = один выпуск-полоса. Но страница-подборка (альбом модели/персонажа)
    // несёт свою рубрику (data-heading-id): её посты — отдельные главы,
    // каждая с собственной полосой. Для альбомов косплея, у которых своей
    // рубрики на странице нет, посты собираются по sitemap-префиксу.

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val document = Jsoup.parse(html, baseUrl)
        val path = response.request.url.encodedPath
        val title = document.title().substringBefore(" | ").trim()
        val publishedAt = document.selectFirst("meta[property=article:published_time], time[datetime]")
            ?.attr("content")
            ?.takeIf { it.isNotEmpty() }

        val subRubricId = Regex("data-heading-id=\"(\\d{3,})\"").find(html)?.groupValues?.get(1)?.toLongOrNull()
        if (subRubricId != null) {
            val articles = runCatching { fetchArticles(subRubricId, page = 1, limit = SUB_RUBRIC_LIMIT) }
                .getOrDefault(emptyList())
            if (articles.size > 1) {
                return articles
                    .sortedBy { it.first_published_at }
                    .mapIndexed { index, article -> chapterFromArticle(article, index + 1) }
            }
        }

        if (path.startsWith("/Cosplay/", ignoreCase = true)) {
            val chapters = cosplayAlbumChapters(title, path)
            if (chapters.isNotEmpty()) return chapters
        }

        return listOf(
            SChapter.create().apply {
                url = path
                name = "Полоса"
                chapter_number = releaseNumber(title)
                date_upload = parsePublished(publishedAt)
            },
        )
    }

    private fun chapterFromArticle(article: ArticleDto, index: Int): SChapter = SChapter.create().apply {
        url = article.url
        name = article.name.trim().ifEmpty { "Фото $index" }
        chapter_number = releaseNumber(name).takeIf { it > 0 } ?: index.toFloat()
        date_upload = parsePublished(article.first_published_at)
    }

    private fun releaseNumber(title: String): Float =
        Regex("""#(\d+(?:[.,]\d+)?)""").find(title)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f

    private fun cosplayAlbumChapters(title: String, albumPath: String): List<SChapter> {
        val slug = slugify(title.substringBefore("/").trim())
        if (slug.length < 3) return emptyList()
        val posts = sitemapEntries().filter { (postPath, _) ->
            val segments = postPath.trim('/').split('/')
            segments.size == 2 &&
                segments.first().startsWith(slug) &&
                !segments.first().equals("cosplay", ignoreCase = true) &&
                !postPath.equals(albumPath, ignoreCase = true)
        }
        if (posts.size < 2) return emptyList()
        return posts
            .sortedBy { it.second }
            .mapIndexed { index, (postPath, lastmod) ->
                val lastSegment = postPath.trim('/').substringAfterLast('/')
                val uuidLike = lastSegment.length == 36 && lastSegment.count { it == '-' } == 4
                val name = if (uuidLike) {
                    "Фото ${index + 1}"
                } else {
                    lastSegment.split('-')
                        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                        .ifEmpty { "Фото ${index + 1}" }
                }
                SChapter.create().apply {
                    url = postPath
                    this.name = name
                    chapter_number = (index + 1).toFloat()
                    date_upload = lastmod
                }
            }
    }

    private fun slugify(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun sitemapEntries(): List<Pair<String, Long>> {
        sitemapCache?.let { return it }
        val entries = runCatching {
            client.newCall(GET("$baseUrl/sitemap.xml", headers)).execute().use { response ->
                val body = response.body.string()
                SITEMAP_BLOCK.findAll(body).mapNotNull { match ->
                    val path = match.groupValues[1].removePrefix(baseUrl).ifEmpty { return@mapNotNull null }
                    val millis = runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(match.groupValues[2])?.time ?: 0L
                    }.getOrDefault(0L)
                    path to millis
                }.toList()
            }
        }.getOrDefault(emptyList())
        if (entries.isNotEmpty()) sitemapCache = entries
        return entries
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
        private const val SEARCH_LIMIT = 90
        private const val SUB_RUBRIC_LIMIT = 100
        private const val PAGE_IMAGE_SELECTOR = "img.cli-article__image, .cr-article__content img, .cr-article img"
        private val SITEMAP_BLOCK = Regex(
            """<loc>([^<]+)</loc>[^<]*(?:<lastmod>([^<]*)</lastmod>)?""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
