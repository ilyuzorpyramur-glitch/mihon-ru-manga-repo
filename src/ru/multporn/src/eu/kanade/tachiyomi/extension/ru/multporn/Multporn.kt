package eu.kanade.tachiyomi.extension.ru.multporn

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/**
 * Multporn — взрослые комиксы и галереи (не только хентай).
 *
 * Каталог: раздел /comics и /manga. Альбом = одна «глава», страницы лежат
 * в /sites/default/files/ и разбиты друпаловским пейджером вида ?page=0,0,0,N.
 */
@Source
abstract class Multporn : KeiSource() {

    // ============================== Популярное ==============================
    override suspend fun getPopularManga(page: Int): MangasPage =
        catalogParse(client.get("$baseUrl/comics?page=${page - 1}", Fetch.DOCUMENT).asJsoup())

    // ============================== Недавние ==============================
    override suspend fun getLatestUpdates(page: Int): MangasPage =
        catalogParse(client.get("$baseUrl/manga?page=${page - 1}", Fetch.DOCUMENT).asJsoup())

    // ============================== Поиск ==============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment("node")
            .addQueryParameter("keys", query.trim())
            .addQueryParameter("page", (page - 1).toString())
            .build()
        return catalogParse(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    private fun catalogParse(document: Document): MangasPage {
        val mangas = document.select("a[href^=/comics/], a[href^=/manga/]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val title = link.text().trim()
                // Отсекаем навигацию: у альбома в пути минимум два сегмента.
                if (title.isEmpty() || href.count { it == '/' } < 3) return@mapNotNull null
                SManga.create().apply {
                    url = href
                    this.title = title
                }
            }
            .distinctBy { it.url }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ============================== Детали ==============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url, Fetch.DOCUMENT).asJsoup()
        val newManga = mangaDetailsParse(document, manga.url)
        val newChapters = if (fetchChapters) listOf(chapterParse(manga.url)) else chapters
        return SMangaUpdate(newManga, newChapters)
    }

    private fun mangaDetailsParse(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        description = document.selectFirst("meta[name=description]")?.attr("content")
        genre = document.select("div.breadcrumb a[href^=/comics/], div.breadcrumb a[href^=/manga/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        status = SManga.UNKNOWN
    }

    private fun chapterParse(mangaUrl: String): SChapter = SChapter.create().apply {
        name = "Альбом"
        url = mangaUrl
        chapter_number = 0f
    }

    // ============================== Страницы ==============================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url, Fetch.DOCUMENT).asJsoup()
        val lastPage = document.select("a[href*='?page=0%2C0%2C0%2C']")
            .mapNotNull { PAGE_INDEX.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0

        val urls = linkedSetOf<String>()
        urls += imageUrls(document)
        for (index in 1..lastPage) {
            val next = client.get("$baseUrl${chapter.url}?page=0,0,0,$index", Fetch.DOCUMENT).asJsoup()
            urls += imageUrls(next)
        }
        if (urls.isEmpty()) throw Exception("Страницы не найдены: сайт изменил разметку")

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun imageUrls(document: Document): List<String> = document
        .select("img[src*=/sites/default/files/]")
        .map { it.absUrl("src") }
        .filter { it.isNotBlank() }

    companion object {
        /** Друпаловский пейджер вида ?page=0%2C0%2C0%2C3 — берём последний индекс. */
        private val PAGE_INDEX = Regex("""0%2C0%2C0%2C([0-9]+)""")
    }
}
