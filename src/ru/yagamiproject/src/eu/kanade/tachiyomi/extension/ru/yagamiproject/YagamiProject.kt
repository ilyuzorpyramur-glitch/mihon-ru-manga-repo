package ru.yagamiproject

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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * YagamiProject (read.yagami.me) — сгенерировано extension-forge (HTML).
 *
 * HTML-источник: каталог /list-new, поиск /reader/search, главы по .list .element, страницы из .web_pictures.
 */
@Source
class YagamiProject(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource() {
    override val supportsLatest = true

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

    private fun text(
        root: Element,
        selector: String,
        attr: String = "",
        after: String = "",
        before: String = "",
        split: String = "",
        pick: String = "",
    ): String {
        if (selector.isEmpty()) return ""
        val element = root.selectFirst(selector) ?: return ""
        var value = if (attr.isEmpty()) element.text() else element.attr(attr)
        if (after.isNotEmpty()) value = value.substringAfter(after, value)
        if (before.isNotEmpty()) value = value.substringBefore(before, value)
        if (split.isNotEmpty()) {
            val parts = value.split(split).map { it.trim() }.filter { it.isNotEmpty() }
            value =
                when (pick) {
                    "min" -> parts.minOrNull() ?: ""
                    "max" -> parts.maxOrNull() ?: ""
                    "last" -> parts.lastOrNull() ?: ""
                    else -> parts.firstOrNull() ?: ""
                }
        }
        return value.trim()
    }

    // =============================== Каталог ===============================

    private fun listRequest(
        page: Int,
        path: String,
    ): Request = GET(baseUrl + path.replace("{page}", page.toString()), headers)

    override fun popularMangaRequest(page: Int): Request = listRequest(page, LIST_PATH)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = listRequest(page, LATEST_PATH)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        if (query.isNotBlank()) {
            val path =
                SEARCH_PATH
                    .replace("{query}", query.trim())
                    .replace("{page}", page.toString())
            return GET(baseUrl + path, headers)
        }
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas =
            document.select(ITEM_SELECTOR).mapNotNull { card ->
                val link = card.selectFirst(LINK_SELECTOR) ?: return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    title = link.attr("title").ifBlank { link.text() }.trim()
                    thumbnail_url =
                        card
                            .selectFirst(COVER_SELECTOR)
                            ?.attr("abs:src")
                            ?.replace(COVER_REPLACE_FROM, COVER_REPLACE_TO)
                            .orEmpty()
                    initialized = false
                }
            }
        val hasMore = HAS_NEXT_SELECTOR.isEmpty() || document.selectFirst(HAS_NEXT_SELECTOR) != null
        return MangasPage(mangas, hasMore)
    }

    // =============================== Детали ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title =
                text(document, TITLE_SELECTOR, after = TITLE_AFTER, before = TITLE_BEFORE, split = TITLE_SPLIT, pick = TITLE_PICK)
                    .ifBlank { document.title() }
            thumbnail_url = document.selectFirst(COVER_DETAIL_SELECTOR)?.attr("abs:src").orEmpty()
            author =
                text(document, AUTHOR_SELECTOR, after = AUTHOR_AFTER, split = AUTHOR_SPLIT, pick = AUTHOR_PICK)
                    .takeIf { it.isNotEmpty() }
            artist =
                text(document, ARTIST_SELECTOR, after = ARTIST_AFTER, split = ARTIST_SPLIT, pick = ARTIST_PICK)
                    .takeIf { it.isNotEmpty() }
            genre = text(document, GENRES_SELECTOR, after = GENRES_AFTER).takeIf { it.isNotEmpty() }
            description =
                buildString {
                    val alt = text(document, ALT_SELECTOR, after = ALT_AFTER)
                    if (alt.isNotEmpty()) append("Альтернативные названия: ").append(alt).append("\n\n")
                    append(text(document, DESC_SELECTOR, after = DESC_AFTER))
                }
            status =
                when (text(document, STATUS_SELECTOR, after = STATUS_AFTER)) {
                    "онгоинг" -> SManga.ONGOING
                    "активный" -> SManga.ONGOING
                    "завершён" -> SManga.COMPLETED

                    else -> SManga.UNKNOWN
                }
            initialized = false
        }
    }

    // ================================ Главы =================================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(CHAPTER_ITEM_SELECTOR).mapNotNull { element ->
            val link = element.selectFirst(CHAPTER_LINK_SELECTOR) ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.attr("title").ifBlank { link.text() }.trim()
                chapter_number = parseChapterNumber(name, link.absUrl("href"))
                date_upload = parseDate(text(element, CHAPTER_DATE_SELECTOR, after = CHAPTER_DATE_AFTER))
                scanlator =
                    element
                        .select(CHAPTER_SCANLATOR_SELECTOR)
                        .joinToString(" / ") { it.text() }
                        .takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun parseChapterNumber(
        name: String,
        href: String,
    ): Float {
        val fromName = Regex("""(\d+(?:[.,]\d+)?)""").find(name)?.groupValues?.get(1)
        val fromHref = Regex("""(\d+(?:[.,]\d+)?)/?$""").find(href)?.groupValues?.get(1)
        return (fromName ?: fromHref)?.replace(',', '.')?.toFloatOrNull() ?: -1f
    }

    private fun parseDate(value: String): Long = when (value) {
        "Сегодня" -> System.currentTimeMillis()
        "Вчера" -> System.currentTimeMillis() - 24 * 60 * 60 * 1000
        else -> runCatching { DATE_FORMAT.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    // =============================== Страницы ===============================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select(PAGE_IMAGE_SELECTOR)
        if (images.isNotEmpty()) {
            return images.mapIndexed { index, image -> Page(index, imageUrl = image.attr("abs:src")) }
        }
        if (PAGE_LINK_SELECTOR.isNotEmpty()) {
            return document.select(PAGE_LINK_SELECTOR).mapIndexed { index, link ->
                Page(index, imageUrl = link.absUrl("href"))
            }
        }
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.US)

        private const val LIST_PATH = "/list-new/{page}"
        private const val LATEST_PATH = "/latest/{page}"
        private const val SEARCH_PATH = "/reader/search/?s={query}&p={page}"
        private const val ITEM_SELECTOR = ".list .group"
        private const val LINK_SELECTOR = ".title a"
        private const val COVER_SELECTOR = ".cover_mini > img"
        private const val COVER_REPLACE_FROM = "thumb_"
        private const val COVER_REPLACE_TO = ""
        private const val HAS_NEXT_SELECTOR = ".panel_nav .button a"

        private const val TITLE_SELECTOR = "title"
        private const val TITLE_AFTER = ""
        private const val TITLE_BEFORE = " :: Yagami"
        private const val TITLE_SPLIT = " :: "
        private const val TITLE_PICK = "min"
        private const val COVER_DETAIL_SELECTOR = ".cover img"
        private const val AUTHOR_SELECTOR = "li:contains(Автор(ы):)"
        private const val AUTHOR_AFTER = "Автор(ы): "
        private const val AUTHOR_SPLIT = " / "
        private const val AUTHOR_PICK = "min"
        private const val ARTIST_SELECTOR = "li:contains(Художник(и):)"
        private const val ARTIST_AFTER = "Художник(и): "
        private const val ARTIST_SPLIT = " / "
        private const val ARTIST_PICK = "min"
        private const val GENRES_SELECTOR = "li:contains(Жанры:)"
        private const val GENRES_AFTER = "Жанры: "
        private const val ALT_SELECTOR = "li:contains(Название:)"
        private const val ALT_AFTER = "Название: "
        private const val DESC_SELECTOR = "li:contains(Описание:)"
        private const val DESC_AFTER = "Описание: "
        private const val STATUS_SELECTOR = "li:contains(Статус перевода:) span"
        private const val STATUS_AFTER = ""

        private const val CHAPTER_ITEM_SELECTOR = ".list .element"
        private const val CHAPTER_LINK_SELECTOR = ".title a"
        private const val CHAPTER_DATE_SELECTOR = ".meta_r"
        private const val CHAPTER_DATE_AFTER = ", "
        private const val CHAPTER_SCANLATOR_SELECTOR = ".meta_r a"

        private const val PAGE_IMAGE_SELECTOR = ".web_pictures img.web_img"
        private const val PAGE_LINK_SELECTOR = ".dropdown li a"
    }
}
