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
 * Каждый пост = один выпуск: одна длинная полоса — img.cli-article__image.
 * Поискового API у CMS нет: без жанра поиск идёт по публичному каталогу сайта
 * (Supabase anime_catalog — 700+ записей с постерами, ключ publishable из фронтенда),
 * при выбранном жанре — по фидам рубрик CMS с параметром tags.
 * Страницы-подборки (альбомы моделей/персонажей) несут свою рубрику
 * (data-heading-id): её посты становятся главами — так доступны все фото.
 * Для альбомов косплея без своей рубрики посты собираются по sitemap-префиксу.
 */
@Source
class AnimeStock(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource() {
    override val supportsLatest = true

    private val json =
        Json {
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

    private fun articlesUrl(
        rubricId: Long,
        page: Int,
        tagId: Long? = null,
        limit: Int = PAGE_SIZE + 1,
    ): HttpUrl = "$baseUrl$API_PATH/rubrics/$rubricId/articles/"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
        .addQueryParameter("deleted", "false")
        .addQueryParameter("published", "true")
        .apply { tagId?.let { addQueryParameter("tags", it.toString()) } }
        .build()

    private fun fetchArticles(
        rubricId: Long,
        page: Int,
        limit: Int = PAGE_SIZE + 1,
    ): List<ArticleDto> {
        val request = GET(articlesUrl(rubricId, page, limit = limit).toString(), headers)
        val response = client.newCall(request).execute()
        val articles = response.parseAs<List<ArticleDto>>(json)
        response.close()
        return articles
    }

    override fun popularMangaRequest(page: Int): Request = GET(articlesUrl(RUBRIC_MANGA, page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseArticles(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(articlesUrl(RUBRIC_MANGA, page), headers)

    // «Последнее» — вся лента сайта: Манга + Хентай, слитно по дате публикации
    override fun latestUpdatesParse(response: Response): MangasPage {
        val offset =
            response.request.url
                .queryParameter("offset")
                ?.toIntOrNull() ?: 0
        val page = offset / PAGE_SIZE + 1
        val manga = fetchArticles(RUBRIC_MANGA, page)
        val hentai = runCatching { fetchArticles(RUBRIC_HENTAI, page) }.getOrDefault(emptyList())
        val merged = (manga + hentai).distinctBy { it.id }.sortedByDescending { it.first_published_at }
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

    // Без жанра ищем по публичному каталогу сайта (Supabase anime_catalog):
    // он покрывает весь сайт, включая альбомы косплея и страницы персонажей.
    // Жанровые теги существуют только в CMS — при выбранном жанре ищем по фидам рубрик.
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val rubric = filters.filterIsInstance<RubricFilter>().firstOrNull()?.selectedRubric ?: RUBRIC_MANGA
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedTag
        if (tag != null) return cmsSearchRequest(page, query, rubric, tag)
        val builder = "$SB_URL/rest/v1/$SB_TABLE".toHttpUrl().newBuilder()
        builder.addQueryParameter("select", "title,url,poster")
        builder.addQueryParameter("limit", SB_LIMIT.toString())
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("q", query)
        builder.addQueryParameter("rubric", rubric.toString())
        return GET(builder.build().toString(), catalogHeaders())
    }

    private fun cmsSearchRequest(
        page: Int,
        query: String,
        rubric: Long,
        tag: String,
    ): Request {
        val rubrics = if (rubric == RUBRIC_ALL) SEARCH_RUBRICS.toList() else listOf(rubric)
        val first = rubrics.first()
        val base = articlesUrl(first, page = 1, tagId = SITE_TAGS[tag]?.get(first), limit = SEARCH_LIMIT)
        val builder = base.newBuilder()
        builder.addQueryParameter("page", page.toString())
        builder.addQueryParameter("q", query)
        builder.addQueryParameter("tag", tag)
        builder.addQueryParameter("rubrics", rubrics.joinToString(","))
        return GET(builder.build().toString(), headers)
    }

    private fun catalogHeaders(): Headers {
        val builder = headersBuilder().newBuilder()
        builder.add("apikey", SB_KEY)
        builder.add("Authorization", "Bearer $SB_KEY")
        builder.add("Accept", "application/json")
        return builder.build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val isCatalog = response.request.url.host == SB_HOST
        if (isCatalog) return catalogSearchParse(response)
        return cmsSearchParse(response)
    }

    private fun catalogSearchParse(response: Response): MangasPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = url.queryParameter("q").orEmpty().trim()
        val rubric = url.queryParameter("rubric")?.toLongOrNull() ?: RUBRIC_MANGA
        val items = response.parseAs<List<CatalogItemDto>>(json)
        val withUrl = items.filter { it.url.startsWith("/") }
        val nonService = withUrl.filter { !SERVICE_PATHS.contains(it.url) }
        val named = nonService.filter { it.title.length > 2 && !it.title.startsWith("Страница без названия") }
        val byRubric = named.filter { rubricMatches(it, rubric) }
        val byQuery = byRubric.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
        val unique = byQuery.distinctBy { it.url }
        val from = (page - 1) * PAGE_SIZE
        val chunk = unique.drop(from).take(PAGE_SIZE)
        return MangasPage(chunk.map { it.toSManga() }, from + PAGE_SIZE < unique.size)
    }

    private fun rubricMatches(
        item: CatalogItemDto,
        rubric: Long,
    ): Boolean {
        if (rubric == RUBRIC_ALL) return true
        val url = item.url.lowercase()
        val title = item.title.lowercase()
        return when (rubric) {
            RUBRIC_MANGA -> url.contains("/manga") || title.contains("манга")
            RUBRIC_HENTAI -> url.contains("/hentai") || url.contains("/khentay") || title.contains("хентай")
            RUBRIC_COSPLAY -> url.contains("kospley") || url.contains("stranitsa") || title.contains("косплей")
            else -> false
        }
    }

    private fun cmsSearchParse(response: Response): MangasPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = url.queryParameter("q").orEmpty().trim()
        val tag = url.queryParameter("tag").orEmpty().ifBlank { null }
        val rubricsParam = url.queryParameter("rubrics")
        val rubrics = rubricsParam?.split(",")?.mapNotNull(String::toLongOrNull) ?: listOf(RUBRIC_MANGA)

        // Первый запрос уже выполнен: используем его ответ, если тег применим к этой рубрике
        val firstTagOk = tag == null || SITE_TAGS[tag]?.containsKey(rubrics.first()) == true
        val collected = if (firstTagOk) response.parseAs<List<ArticleDto>>(json).toMutableList() else mutableListOf()

        for (rubric in rubrics.drop(1)) {
            val tagId = tag?.let { SITE_TAGS[it]?.get(rubric) } ?: if (tag == null) null else continue
            val request = GET(articlesUrl(rubric, page = 1, tagId = tagId, limit = SEARCH_LIMIT).toString(), headers)
            val sub = client.newCall(request).execute()
            val items = sub.parseAs<List<ArticleDto>>(json)
            sub.close()
            collected += items
        }

        val unique = collected.distinctBy { it.id }
        val valid = unique.filter { it.url.isNotBlank() && it.name.isNotBlank() }
        val matched = valid.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val filtered = matched.sortedByDescending { it.first_published_at }
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
        val meta = document.selectFirst("meta[property=article:published_time], time[datetime]")
        val publishedAt = meta?.attr("content")?.takeIf { it.isNotEmpty() }

        val subRubricId =
            Regex("data-heading-id=\"(\\d{3,})\"")
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        if (subRubricId != null) {
            val fetched = runCatching { fetchArticles(subRubricId, page = 1, limit = SUB_RUBRIC_LIMIT) }
            val subArticles = fetched.getOrDefault(emptyList())
            if (subArticles.size > 1) {
                val ordered = subArticles.sortedBy { it.first_published_at }
                return ordered.mapIndexed { index, article -> chapterFromArticle(article, index + 1) }
            }
        }

        if (path.startsWith("/Cosplay/", ignoreCase = true)) {
            val chapters = cosplayAlbumChapters(title, path)
            if (chapters.isNotEmpty()) return chapters
        }

        val single = SChapter.create()
        single.url = path
        single.name = "Полоса"
        single.chapter_number = releaseNumber(title)
        single.date_upload = parsePublished(publishedAt)
        return listOf(single)
    }

    private fun chapterFromArticle(
        article: ArticleDto,
        index: Int,
    ): SChapter {
        val name = article.name.trim().ifEmpty { "Фото $index" }
        val chapter = SChapter.create()
        chapter.url = article.url
        chapter.name = name
        chapter.chapter_number = releaseNumber(name).takeIf { it > 0 } ?: index.toFloat()
        chapter.date_upload = parsePublished(article.first_published_at)
        return chapter
    }

    private fun releaseNumber(title: String): Float {
        val match = Regex("#(\\d+(?:[.,]\\d+)?)").find(title)
        val raw = match?.groupValues?.get(1) ?: return -1f
        return raw.replace(',', '.').toFloatOrNull() ?: -1f
    }

    private fun cosplayAlbumChapters(
        title: String,
        albumPath: String,
    ): List<SChapter> {
        val slug = slugify(title.substringBefore("/").trim())
        if (slug.length < 3) return emptyList()
        val all = sitemapEntries()
        val posts = all.filter { (postPath, _) -> albumChapterMatch(postPath, slug, albumPath) }
        if (posts.size < 2) return emptyList()
        val ordered = posts.sortedBy { it.second }
        return ordered.mapIndexed { index, (postPath, lastmod) -> albumChapter(postPath, lastmod, index) }
    }

    private fun albumChapterMatch(
        postPath: String,
        slug: String,
        albumPath: String,
    ): Boolean {
        val segments = postPath.trim('/').split('/')
        if (segments.size != 2) return false
        if (!segments.first().startsWith(slug)) return false
        if (segments.first().equals("cosplay", ignoreCase = true)) return false
        return !postPath.equals(albumPath, ignoreCase = true)
    }

    private fun albumChapter(
        postPath: String,
        lastmod: Long,
        index: Int,
    ): SChapter {
        val lastSegment = postPath.trim('/').substringAfterLast('/')
        val uuidLike = lastSegment.length == 36 && lastSegment.count { it == '-' } == 4
        val words = lastSegment.split('-').map { it.replaceFirstChar { c -> c.uppercase() } }
        val name = if (uuidLike) "Фото ${index + 1}" else words.joinToString(" ").ifEmpty { "Фото ${index + 1}" }
        val chapter = SChapter.create()
        chapter.url = postPath
        chapter.name = name
        chapter.chapter_number = (index + 1).toFloat()
        chapter.date_upload = lastmod
        return chapter
    }

    private fun slugify(value: String): String {
        val cleaned = value.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        return cleaned.trim('-')
    }

    private fun sitemapEntries(): List<Pair<String, Long>> {
        val cached = sitemapCache
        if (cached != null) return cached
        val entries = runCatching { fetchSitemap() }.getOrDefault(emptyList())
        if (entries.isNotEmpty()) sitemapCache = entries
        return entries
    }

    private fun fetchSitemap(): List<Pair<String, Long>> {
        val request = GET("$baseUrl/sitemap.xml", headers)
        val response = client.newCall(request).execute()
        val result = parseSitemap(response.body.string())
        response.close()
        return result
    }

    private fun parseSitemap(body: String): List<Pair<String, Long>> {
        val result = ArrayList<Pair<String, Long>>()
        SITEMAP_BLOCK.findAll(body).forEach { match ->
            val path = match.groupValues[1].removePrefix(baseUrl)
            val lastmod = match.groupValues[2]
            if (path.isNotEmpty() && lastmod.isNotEmpty()) {
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(lastmod)
                val millis = parsed?.time ?: 0L
                result.add(path to millis)
            }
        }
        return result
    }

    private fun parsePublished(value: String?): Long {
        if (value.isNullOrEmpty()) return 0L
        val utc = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0L)
        if (utc != 0L) return utc
        val parsed = runCatching { LocalDateTime.parse(value.take(19)) }.getOrNull()
        if (parsed == null) return 0L
        val zoned = parsed.atZone(ZoneId.of("Europe/Moscow"))
        return zoned.toInstant().toEpochMilli()
    }

    // =============================== Страницы ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val raw = document.select(PAGE_IMAGE_SELECTOR).map { it.attr("abs:src") }
        val images = raw.filter { it.startsWith("http") }.distinct()
        if (images.isNotEmpty()) return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
        val cover = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
        if (cover != null && cover.isNotEmpty()) return listOf(Page(0, imageUrl = cover))
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
    private data class RubricDto(
        val id: Long = 0,
        val name: String = "",
    )

    @Serializable
    private data class CatalogItemDto(
        val title: String = "",
        val url: String = "",
        val poster: String = "",
    )

    private fun CatalogItemDto.toSManga(): SManga {
        val cleanTitle =
            title
                .replace(Regex("\\s*/AnimeStock.*$"), "")
                .replace(Regex("\\s*:\\s*Слив Косплея.*$"), "")
                .trim()
        val manga = SManga.create()
        manga.url = url
        manga.title = cleanTitle
        manga.thumbnail_url = poster.takeIf { it.startsWith("http") }
        manga.initialized = false
        return manga
    }

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
        private const val SB_URL = "https://updzooucvimmlzftytke.supabase.co"
        private const val SB_HOST = "updzooucvimmlzftytke.supabase.co"
        private const val SB_TABLE = "anime_catalog"
        private const val SB_LIMIT = 1000
        private val SERVICE_PATHS =
            setOf("/", "/feedback", "/other", "/Nav", "/undress", "/profile", "/models", "/info", "/admin001", "/admin002", "/admin003")
        private const val PAGE_IMAGE_SELECTOR = "img.cli-article__image, .cr-article__content img, .cr-article img"
        private val SITEMAP_BLOCK =
            Regex(
                "<loc>([^<]+)</loc>[^<]*(?:<lastmod>([^<]*)</lastmod>)?",
                RegexOption.DOT_MATCHES_ALL,
            )
    }
}
