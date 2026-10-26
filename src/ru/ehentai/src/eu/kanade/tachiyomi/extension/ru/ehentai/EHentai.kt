package eu.kanade.tachiyomi.extension.ru.ehentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
        // E-Hentai жёстко ограничивает частоту запросов.
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

    override suspend fun getPopularManga(page: Int): MangasPage = parseList(client.get("$baseUrl/popular?page=$page").asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        parseList(client.get("$baseUrl/?f_search=$LANGUAGE_TAG&page=$page".toHttpUrl()).asJsoup())

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val tagState =
            filters.filterIsInstance<RussianOnlyFilter>().firstOrNull()?.state
                ?: Filter.TriState.STATE_INCLUDE

        val terms =
            buildList {
                if (tagState != Filter.TriState.STATE_EXCLUDE) add(LANGUAGE_TAG)
                if (query.isNotBlank()) add(query.trim())
            }.joinToString(" ")

        val url =
            "$baseUrl/"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("f_search", terms)
                .addQueryParameter("page", page.toString())
                .build()

        return parseList(client.get(url).asJsoup())
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
        val document = client.get("$baseUrl${manga.url}").asJsoup()

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
        val galleryUrl = "$baseUrl${chapter.url}"

        // Собираем ссылки на страницы просмотра со всех страниц превью.
        val pageLinks = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var nextUrl: String? = galleryUrl

        while (nextUrl != null && visited.add(nextUrl)) {
            val document = client.get(nextUrl).asJsoup()

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
                    .get(url)
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
    )

    class RussianOnlyFilter : Filter.TriState("Только русский перевод")

    // ───────────────────────────── Настройки ─────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_RUSSIAN_ONLY
                title = "Только русский перевод"
                summary = "Добавлять тег language:russian в поиск"
                setDefaultValue(true)
            }.also(screen::addPreference)

        EditTextPreference(screen.context)
            .apply {
                key = PREF_MAX_PAGES
                title = "Максимум страниц в галерее"
                summary = "Ограничение на количество загружаемых изображений (1–1000)"
                setDefaultValue("100")
            }.also(screen::addPreference)
    }

    companion object {
        private const val LANGUAGE_TAG = "language:russian"
        private const val PAGE_SIZE = 25
        private const val PREF_RUSSIAN_ONLY = "pref_russian_only"
        private const val PREF_MAX_PAGES = "pref_max_pages"
        private val GALLERY_REGEX = Regex("""/g/\d+/[0-9a-f]+/""")
    }
}
