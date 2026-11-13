package ru.mangalove

import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * MangaLove (mangalove.ru) — сгенерировано extension-forge.
 *
 * API-ключ X-API-Key (из фронтенда). Сортировки: popular, rating, latest, siteLatest. Главы ≤100/страница, каталог ≤50/страницу. Поля-числа приходят то строкой, то числом.
 */
@Source
class MangaLove(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    override val id: Long,
) : HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences by getPreferencesLazy()

    private val baseUrlHost = baseUrl.toHttpUrl().host

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30.seconds)
        .readTimeout(60.seconds)
        .rateLimit(4, 1.seconds) { it.host == baseUrlHost }
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(API_KEY_HEADER, API_KEY)
                    .build(),
            )
        }
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("Referer", "$baseUrl/")
        add("User-Agent", "Mozilla/5.0 (Linux; Android 14) MangaLove/1.0")
    }

    // ================================ Качество ================================

    private val preferredQuality
        get() = preferences.getString(QUALITY_PREF, DEFAULT_QUALITY) ?: DEFAULT_QUALITY

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = "Качество изображений"
            entries = QUALITY_LABELS
            entryValues = QUALITY_IDS
            summary = "%s"
            setDefaultValue(DEFAULT_QUALITY)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Для применения очистите кэш глав (Настройки → Дополнительно)",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.let(screen::addPreference)
    }

    // ============================== Каталог/поиск ==============================

    private fun catalogUrl(page: Int, sort: String, query: String? = null): HttpUrl = "$baseUrl$CATALOG_PATH".toHttpUrl().newBuilder()
        .addQueryParameter("lang", lang)
        .addQueryParameter("sort", sort)
        .addQueryParameter("page", page.toString())
        .addQueryParameter("per_page", PER_PAGE.toString())
        .apply {
            query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
        }
        .build()

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page, DEFAULT_SORT), headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<CatalogDto>(json)
        return MangasPage(data.manga.map { it.toSManga() }, data.hasMore)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, LATEST_SORT), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sort = DEFAULT_SORT
        var status = ""
        var ageRating = ""
        var includedTags = ""
        var excludedTags = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.selected
                is StatusFilter -> status = filter.selected
                is AgeRatingFilter -> ageRating = filter.selected
                is GenreFilter -> {
                    includedTags = filter.state
                        .filter { it.state == Filter.TriState.STATE_INCLUDE }
                        .joinToString(",") { it.id }
                    excludedTags = filter.state
                        .filter { it.state == Filter.TriState.STATE_EXCLUDE }
                        .joinToString(",") { it.id }
                }
                else -> {}
            }
        }

        val url = catalogUrl(page, sort, query).newBuilder()
        if (status.isNotEmpty()) url.addQueryParameter("status", status)
        if (ageRating.isNotEmpty()) url.addQueryParameter("age_rating", ageRating)
        if (includedTags.isNotEmpty()) url.addQueryParameter("included_tags", includedTags)
        if (excludedTags.isNotEmpty()) url.addQueryParameter("excluded_tags", excludedTags)
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ================================ Детали ================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + DETAILS_PATH.replace("{id}", manga.url), headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<MangaDto>(json).toSManga()

    // ================================= Главы =================================

    override fun chapterListRequest(manga: SManga): Request = chapterPageRequest(manga.url, 1)

    private fun chapterPageRequest(mangaId: String, page: Int): Request = GET(
        baseUrl + CHAPTERS_PATH.replace("{id}", mangaId) +
            "?lang=" + lang + "&page=" + page,
        headers,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val segments = response.request.url.pathSegments
        val mangaId = segments.getOrNull(segments.indexOf("manga") + 1) ?: return emptyList()

        var data = response.parseAs<ChaptersDto>(json)
        val chapters = data.chapters.toMutableList()
        var page = 1

        while (chapters.isNotEmpty() && chapters.size < data.total && page < MAX_CHAPTER_PAGES) {
            page++
            data = client.newCall(chapterPageRequest(mangaId, page)).execute().parseAs(json)
            if (data.chapters.isEmpty()) break
            chapters += data.chapters
        }

        return chapters.distinctBy { it.id }.map { it.toSChapter(mangaId) }
    }

    private fun ChapterDto.toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        url = PAGES_PATH
            .replace("{id}", mangaId)
            .replace("{chapter}", id)
        name = buildString {
            if (volume.isNotEmpty() && volume != "0") append("Том $volume ")
            append("Глава $chapterNum")
            if (title.isNotEmpty()) append(" — $title")
        }
        chapter_number = chapterNum.toFloatOrNull() ?: -1f
        scanlator = scanlationGroup.takeIf { it.isNotEmpty() }
        date_upload = publishedAt.toEpochMillis()
    }

    private fun String.toEpochMillis(): Long = runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ================================ Страницы ================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}?quality=$preferredQuality", headers)

    override fun pageListParse(response: Response): List<Page> = response.parseAs<PagesDto>(json).pages.mapIndexed { index, url ->
        Page(index, imageUrl = url)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // ================================ Маппинг ================================

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = id
        title = titleRu.ifBlank { title }
        thumbnail_url = coverUrl.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("http")) it else baseUrl + it
        }
        author = this@toSManga.author.takeIf { it.isNotEmpty() }
        artist = this@toSManga.artist.takeIf { it.isNotEmpty() }
        description = this@toSManga.descriptionRu.ifBlank { this@toSManga.description }
        genre = (genres + themes).distinct().joinToString(", ").takeIf { it.isNotEmpty() }
        status = this@toSManga.status.toSMangaStatus()
        initialized = false
    }

    private fun String.toSMangaStatus(): Int = when (this) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ================================ Фильтры ================================

    private class SortFilter :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("По популярности", "По рейтингу", "Последние обновления", "Новое на сайте"),
        ) {
        val selected get() = arrayOf("popular", "rating", "latest", "siteLatest")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Статус",
            arrayOf("Любой", "Выходит", "Завершено", "Перерыв", "Брошено"),
        ) {
        val selected get() = arrayOf("", "ongoing", "completed", "hiatus", "dropped")[state]
    }

    private class AgeRatingFilter :
        Filter.Select<String>(
            "Возрастной рейтинг",
            arrayOf("Любой", "16+", "18+"),
        ) {
        val selected get() = arrayOf("", "16", "18")[state]
    }
    private class GenreFilter :
        Filter.Group<Genre>(
            "Жанры",
            listOf(
                Genre("4-Koma", "b11fda93-8f1d-4bef-b2ed-8803d3733170"),
                Genre("Action", "391b0423-d847-456f-aff0-8b0cfc03066b"),
                Genre("Adaptation", "f4122d1c-3b44-44d0-9936-ff7502c39ad3"),
                Genre("Adventure", "87cc87cd-a395-47af-b27a-93258283bbc6"),
                Genre("Aliens", "e64f6742-c834-471d-8d72-dd51fc02b835"),
                Genre("Animals", "3de8c75d-8ee3-48ff-98ee-e20a65c86451"),
                Genre("Anthology", "51d83883-4103-437c-b4b1-731cb73d786c"),
                Genre("Award Winning", "0a39b5a1-b235-4886-a747-1d05d216532d"),
                Genre("Boys' Love", "5920b825-4181-4a17-beeb-9918b0ff7a30"),
                Genre("Comedy", "4d32cc48-9f00-4cca-9b5a-a839f0764984"),
                Genre("Cooking", "ea2bc92d-1c26-4930-9b7c-d5c0dc1b6869"),
                Genre("Crime", "5ca48985-9a9d-4bd8-be29-80dc0303db72"),
                Genre("Crossdressing", "9ab53f92-3eed-4e9b-903a-917c86035ee3"),
                Genre("Delinquents", "da2d50ca-3018-4cc0-ac7a-6b7d472a29ea"),
                Genre("Demons", "39730448-9a5f-48a2-85b0-a70db87b1233"),
                Genre("Doujinshi", "b13b2a48-c720-44a9-9c77-39c9979373fb"),
                Genre("Drama", "b9af3a63-f058-46de-a9a0-e0c13906197a"),
                Genre("Fan Colored", "7b2ce280-79ef-4c09-9b58-12b7c23a9b78"),
                Genre("Fantasy", "cdc58593-87dd-415e-bbc0-2ec27bf404cc"),
                Genre("Full Color", "f5ba408b-0e7a-484d-8d49-4e9125ac96de"),
                Genre("Genderswap", "2bd2e8d0-f146-434a-9b51-fc9ff2c5fe6a"),
                Genre("Ghosts", "3bb26d85-09d5-4d2e-880c-c34b974339e9"),
                Genre("Girls' Love", "a3c67850-4684-404e-9b7f-c69850ee5da6"),
                Genre("Gyaru", "fad12b5e-68ba-460e-b933-9ae8318f5b65"),
                Genre("Harem", "aafb99c1-7f60-43fa-b75f-fc9502ce29c7"),
                Genre("Historical", "33771934-028e-4cb3-8744-691e866a923e"),
                Genre("Horror", "cdad7e68-1419-41dd-bdce-27753074a640"),
                Genre("Incest", "5bd0e105-4481-44ca-b6e7-7544da56b1a3"),
                Genre("Isekai", "ace04997-f6bd-436e-b261-779182193d3d"),
                Genre("Loli", "2d1f5d56-a1e5-4d0d-a961-2193588b08ec"),
                Genre("Long Strip", "3e2b8dae-350e-4ab8-a8ce-016e844b9f0d"),
                Genre("Mafia", "85daba54-a71c-4554-8a28-9901a8b0afad"),
                Genre("Magic", "a1f53773-c69a-4ce5-8cab-fffcd90b1565"),
                Genre("Magical Girls", "81c836c9-914a-4eca-981a-560dad663e73"),
                Genre("Mahjong", "cb562697-929f-4d28-9d66-6d3995bf2592"),
                Genre("Martial Arts", "799c202e-7daa-44eb-9cf7-8a3c0441531e"),
                Genre("Mecha", "50880a9d-5440-4732-9afb-8f457127e836"),
                Genre("Medical", "c8cbe35b-1b2b-4a3f-9c37-db84c4514856"),
                Genre("Military", "ac72833b-c4e9-4878-b9db-6c8a4a99444a"),
                Genre("Monster Girls", "dd1f77c5-dea9-4e2b-97ae-224af09caf99"),
                Genre("Monsters", "36fd93ea-e8b8-445e-b836-358f02b3d33d"),
                Genre("Music", "f42fbf9e-188a-447b-9fdc-f19dc1e4d685"),
                Genre("Mystery", "ee968100-4191-4968-93d3-f82d72be7e46"),
                Genre("Ninja", "489dd859-9b61-4c37-af75-5b18e88daafc"),
                Genre("Office Workers", "92d6d951-ca5e-429c-ac78-451071cbf064"),
                Genre("Official Colored", "320831a8-4026-470b-94f6-8353740e6f04"),
                Genre("Oneshot", "0234a31e-a729-4e28-9d6a-3f87c4966b9e"),
                Genre("Philosophical", "b1e97889-25b4-4258-b28b-cd7f4d28ea9b"),
                Genre("Police", "df33b754-73a3-4c54-80e6-1a74a8058539"),
                Genre("Post-Apocalyptic", "9467335a-1b83-4497-9231-765337a00b96"),
                Genre("Psychological", "3b60b75c-a2d7-4860-ab56-05f391bb889c"),
                Genre("Reincarnation", "0bc90acb-ccc1-44ca-a34a-b9f3a73259d0"),
                Genre("Reverse Harem", "65761a2a-415e-47f3-bef2-a9dababba7a6"),
                Genre("Romance", "423e2eae-a7a2-4a8b-ac03-a8351462d71d"),
                Genre("Samurai", "81183756-1453-4c81-aa9e-f6e1b63be016"),
                Genre("School Life", "caaa44eb-cd40-4177-b930-79d3ef2afe87"),
                Genre("Sci-Fi", "256c8bd9-4904-4360-bf4f-508a76d67183"),
                Genre("Self-Published", "891cf039-b895-47f0-9229-bef4c96eccd4"),
                Genre("Shota", "ddefd648-5140-4e5f-ba18-4eca4071d19b"),
                Genre("Slice of Life", "e5301a23-ebd9-49dd-a0cb-2add944c7fe9"),
                Genre("Sports", "69964a64-2f90-4d33-beeb-f3ed2875eb4c"),
                Genre("Superhero", "7064a261-a137-4d3a-8848-2d385de3a99c"),
                Genre("Supernatural", "eabc5b4c-6aff-42f3-b657-3e90cbd00b75"),
                Genre("Survival", "5fff9cde-849c-4d78-aab0-0d52b2ee1d25"),
                Genre("Thriller", "07251805-a27e-4d59-b488-f0bfbec15168"),
                Genre("Time Travel", "292e862b-2d17-4062-90a2-0356caa4ae27"),
                Genre("Traditional Games", "31932a7e-5b8e-49a6-9f12-2afa39dc544c"),
                Genre("Tragedy", "f8f62932-27da-4fe4-8ee1-6779a8c5edba"),
                Genre("Vampires", "d7d1730f-6eb0-4ba6-9437-602cac38664c"),
                Genre("Video Games", "9438db5a-7e2a-4ac0-b39e-e0d95a34b8a8"),
                Genre("Villainess", "d14322ac-4d6f-4e9b-afd9-629d5f4d8a41"),
                Genre("Virtual Reality", "8c86611e-fab7-4986-9dec-d1a2f44acdd5"),
                Genre("Web Comic", "e197df38-d0e7-43b5-9b09-2842d0c326dd"),
                Genre("Wuxia", "acc803a4-c95a-4c22-86fc-eb6b582d82a2"),
                Genre("Zombies", "631ef465-9aba-4afb-b0fc-ea10efe274a8"),
            ),
        )

    private class Genre(name: String, val id: String) : Filter.TriState(name)

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        AgeRatingFilter(),
        GenreFilter(),
    )

    companion object {
        private const val API_KEY_HEADER = "X-API-Key"
        private const val API_KEY = "a5e6bd2ad6b0b838a4d332757981f03a5639c937c4d92a9af9ddf7e330da94ad"
        private const val QUALITY_PREF = "preferred_quality"
        private const val DEFAULT_QUALITY = "data"
        private const val CATALOG_PATH = "/api/v1/manga/catalog"
        private const val DETAILS_PATH = "/api/v1/manga/{id}"
        private const val CHAPTERS_PATH = "/api/v1/manga/{id}/chapters"
        private const val PAGES_PATH = "/api/v1/manga/{id}/chapters/{chapter}/pages"
        private const val PER_PAGE = 50
        private const val MAX_CHAPTER_PAGES = 20
        private const val DEFAULT_SORT = "popular"
        private const val LATEST_SORT = "siteLatest"
        private val QUALITY_IDS = arrayOf("data", "data-saver")
        private val QUALITY_LABELS = arrayOf("Полное качество", "Экономное (data-saver)")
    }
}
