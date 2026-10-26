package eu.kanade.tachiyomi.extension.ru.ehentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class EHentai :
    KeiSource(),
    ConfigurableSource {
    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        // У E-Hentai жёсткий лимит частоты запросов.
        rateLimit(2)
    }

    private val maxPages: Int
        get() = preferences.getString(PREF_MAX_PAGES, "100")?.toIntOrNull()?.coerceIn(1, 1000) ?: 100

    override fun imageRequest(page: Page): Request = super
        .imageRequest(page)
        .newBuilder()
        .header("Referer", "$baseUrl/")
        .build()

    // ───────────────────────────── Каталог ─────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage = parseList(client.get("$baseUrl/popular?page=$page".toHttpUrl()).asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseList(client.get("$baseUrl/?f_search=$LANGUAGE_TAG&page=$page".toHttpUrl()).asJsoup())

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val builder =
            "$baseUrl/"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("page", page.toString())

        var excludedCategories = 0
        var russianOnly = true

        filters.forEach { filter ->
            when (filter) {
                is RussianOnlyFilter -> {
                    russianOnly = filter.state != Filter.TriState.STATE_EXCLUDE
                }

                is CategoryGroup -> {
                    excludedCategories =
                        filter.state
                            .filter { !it.state }
                            .sumOf { it.bit }
                }

                is MinRatingFilter -> {
                    filter.selected()?.let {
                        builder.addQueryParameter("f_srdd", it)
                    }
                }

                is PagesGroup -> {
                    filter.state
                        .firstOrNull { it is PagesFrom }
                        ?.state
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { builder.addQueryParameter("f_spf", it) }
                    filter.state
                        .firstOrNull { it is PagesTo }
                        ?.state
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { builder.addQueryParameter("f_spt", it) }
                }

                is SearchNamesFilter -> {
                    if (filter.state) builder.addQueryParameter("f_sh", "1")
                }

                else -> {}
            }
        }

        if (excludedCategories != 0) {
            builder.addQueryParameter("f_cats", excludedCategories.toString())
        }

        val terms =
            buildList {
                if (russianOnly) add(LANGUAGE_TAG)
                if (query.isNotBlank()) add(query.trim())
            }.joinToString(" ")

        if (terms.isNotEmpty()) builder.addQueryParameter("f_search", terms)

        return parseList(client.get(builder.build()).asJsoup())
    }

    private fun parseList(document: Document): MangasPage {
        val mangas =
            document
                .select("table.itg tr")
                .mapNotNull(::parseRow)
                .distinctBy { it.url }

        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    private fun parseRow(row: Element): SManga? {
        val link = row.selectFirst("a[href*=\"/g/\"]") ?: return null
        if (!GALLERY_REGEX.containsMatchIn(link.absUrl("href"))) return null

        val title =
            row.selectFirst(".glink")?.text()?.takeIf { it.isNotBlank() }
                ?: row.selectFirst("img[title]")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            this.title = title
            thumbnail_url =
                row.selectFirst("img")?.let {
                    it.absUrl("data-src").ifBlank { it.absUrl("src") }
                }
        }
    }

    // ───────────────────────────── Тайтл ─────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}".toHttpUrl()).asJsoup()

        val details = parseDetails(document, manga)
        val chapterList = if (fetchChapters) listOf(parseChapter(manga)) else chapters

        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(
        document: Document,
        manga: SManga,
    ): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("#gn")?.text()?.takeIf { it.isNotBlank() } ?: manga.title
        thumbnail_url = document.selectFirst("#gd1 img, #gdc img")?.let {
            it.absUrl("data-src").ifBlank { it.absUrl("src") }
        } ?: manga.thumbnail_url

        genre =
            document
                .select("#taglist a")
                .eachText()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("Russian", ignoreCase = true) }
                .distinct()
                .joinToString()

        status = SManga.COMPLETED

        val info =
            document
                .select("#gdd tr, #gdd .gdtm")
                .eachText()
                .filter { it.isNotBlank() }
                .joinToString("\n")

        description =
            listOf(info, document.selectFirst("#gj")?.text().orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
    }

    private fun parseChapter(manga: SManga): SChapter = SChapter.create().apply {
        url = manga.url
        name = "Галерея"
        chapter_number = 1f
    }

    // ───────────────────────────── Страницы ─────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageLinks = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var nextUrl: String? = "$baseUrl${chapter.url}"

        while (nextUrl != null && visited.add(nextUrl)) {
            val document = client.get(nextUrl.toHttpUrl()).asJsoup()

            pageLinks +=
                document
                    .select("#gdt a[href*=\"/s/\"]")
                    .map { it.absUrl("href") }
                    .filter { it.isNotBlank() }

            nextUrl =
                document
                    .select("#gdt a[href*=\"?p=\"]")
                    .map { it.absUrl("href") }
                    .firstOrNull { it !in visited }

            if (pageLinks.size >= maxPages) break
        }

        val uniqueLinks = pageLinks.distinct().take(maxPages)

        return uniqueLinks.mapIndexedNotNull { index, url ->
            val imageUrl =
                client
                    .get(url.toHttpUrl())
                    .asJsoup()
                    .selectFirst("#img")
                    ?.absUrl("src")
            imageUrl?.takeIf { it.isNotBlank() }?.let { Page(index, url = url, imageUrl = it) }
        }
    }

    // ───────────────────────────── Фильтры ─────────────────────────────

    override val supportsFilterFetching: Boolean = false

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        RussianOnlyFilter(),
        CategoryGroup(),
        MinRatingFilter(),
        PagesGroup(),
        SearchNamesFilter(),
    )

    class RussianOnlyFilter : Filter.TriState("Только русский перевод")

    class CategoryFilter(
        name: String,
        val bit: Int,
    ) : Filter.CheckBox(name, true)

    class CategoryGroup :
        Filter.Group<CategoryFilter>(
            "Категории",
            listOf(
                CategoryFilter("Doujinshi", 2),
                CategoryFilter("Manga", 4),
                CategoryFilter("Artist CG", 8),
                CategoryFilter("Game CG", 16),
                CategoryFilter("Western", 32),
                CategoryFilter("Non-H", 64),
                CategoryFilter("Image Set", 128),
                CategoryFilter("Cosplay", 256),
                CategoryFilter("Asian Porn", 512),
                CategoryFilter("Misc", 1),
            ),
        )

    class MinRatingFilter :
        Filter.Select<String>(
            "Минимальный рейтинг",
            arrayOf("Любой", "2+", "3+", "4+", "5"),
        ) {
        fun selected(): String? = if (state == 0) null else (state + 1).toString()
    }

    class PagesFrom : Filter.Text("От")

    class PagesTo : Filter.Text("До")

    class PagesGroup : Filter.Group<Filter<String>>("Количество страниц", listOf(PagesFrom(), PagesTo()))

    class SearchNamesFilter : Filter.CheckBox("Искать только в названиях галерей", false)

    // ───────────────────────────── Настройки ─────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context)
            .apply {
                key = PREF_MAX_PAGES
                title = "Максимум страниц в галерее"
                summary = "Ограничение загружаемых изображений, 1–1000 (по умолчанию 100)"
                setDefaultValue("100")
            }.also(screen::addPreference)
    }

    companion object {
        private const val LANGUAGE_TAG = "language:russian"
        private const val PAGE_SIZE = 25
        private const val PREF_MAX_PAGES = "pref_max_pages"
        private val GALLERY_REGEX = Regex("/g/\\d+/[0-9a-f]+/")
    }
}
