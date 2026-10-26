package eu.kanade.tachiyomi.extension.ru.ehentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class EHentai : KeiSource() {

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Referer", "$baseUrl/")
        .build()

    // ── Каталог ──────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage =
        parseList(client.get("$baseUrl/popular?page=$page").asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(
        parseList(client.get("$baseUrl/?f_search=$LANGUAGE_TAG&page=$page").asJsoup()).mangas,
        true,
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val onlyRussian = filters.filterIsInstance<RussianOnlyFilter>().firstOrNull()?.state != false

        val terms = buildList {
            if (onlyRussian) add(LANGUAGE_TAG)
            if (query.isNotBlank()) add(query.trim())
        }.joinToString(" ")

        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("f_search", terms)
            .addQueryParameter("page", page.toString())
            .build()

        return MangasPage(parseList(client.get(url).asJsoup()).mangas, true)
    }

    private fun parseList(document: Document): MangasPage {
        val mangas = document.select("div.itg").mapNotNull(::parseItem).distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    private fun parseItem(element: Element): SManga? {
        val link = element.selectFirst("a[href*=\"/g/\"]") ?: return null
        val href = link.absUrl("href")
        if (!href.contains("/g/")) return null

        val title = element.selectFirst(".glink")?.text()?.takeIf { it.isNotBlank() }
            ?: link.text().takeIf { it.isNotBlank() }
            ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = element.selectFirst("img")?.let {
                it.absUrl("data-src").ifBlank { it.absUrl("src") }
            }
        }
    }

    // ── Тайтл и главы ────────────────────────────────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()

        val updated = parseDetails(document, manga)
        val chapterList = if (fetchChapters) listOf(parseChapter(manga)) else chapters

        return SMangaUpdate(updated, chapterList)
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("#gn")?.text()?.takeIf { it.isNotBlank() } ?: manga.title
        thumbnail_url = document.selectFirst("#gd1 img, #gdc img")?.absUrl("src")
        genre = document.select("#taglist a, .gt a").eachText()
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
        status = SManga.COMPLETED
        description = document.select("#gdd tr").eachText()
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun parseChapter(manga: SManga): SChapter = SChapter.create().apply {
        url = manga.url
        name = "Галерея"
        chapter_number = 1f
    }

    // ── Страницы ─────────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()

        val thumbPages = buildList {
            add(client.get("$baseUrl${chapter.url}").asJsoup())
            // Дополнительные страницы превью: ?p=1, ?p=2...
            document.select("#gdt a[href*=\"?p=\"]").map { it.absUrl("href") }
                .distinct()
                .forEach { add(client.get(it).asJsoup()) }
        }

        val pageUrls = thumbPages.flatMap { doc ->
            doc.select("#gdt a[href*=\"/s/\"]").map { it.absUrl("href") }
        }.distinct()

        val imageUrls = coroutineScope {
            pageUrls.chunked(PAGE_CONCURRENCY).flatMap { chunk ->
                chunk.map { url ->
                    async { client.get(url).asJsoup().selectFirst("#img")?.absUrl("src") }
                }.awaitAll()
            }
        }

        return imageUrls.mapIndexedNotNull { index, imageUrl ->
            imageUrl?.let { Page(index, url = pageUrls.getOrNull(index).orEmpty(), imageUrl = it) }
        }
    }

    override val supportsFilterFetching: Boolean = false

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        RussianOnlyFilter(),
    )

    class RussianOnlyFilter : Filter.TriState("Только русский перевод")

    companion object {
        private const val LANGUAGE_TAG = "language:russian"
        private const val PAGE_SIZE = 20
        private const val PAGE_CONCURRENCY = 4
    }
}
