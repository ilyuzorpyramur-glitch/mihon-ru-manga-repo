package eu.kanade.tachiyomi.extension.ru.mangalove

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaDto(
    val id: String,
    val title: String,
    @SerialName("title_ru") val titleRu: String? = null,
    @SerialName("title_en") val titleEn: String? = null,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val status: String = "",
    val year: Int? = null,
    val author: String? = null,
    val artist: String? = null,
    @SerialName("chapters_count") val chaptersCount: String? = null,
    @SerialName("rating_avg") val ratingAvg: Double? = null,
) {

    fun toSManga(base: String) = SManga.create().apply {
        url = id
        title = this@MangaDto.title
        thumbnail_url = coverUrl?.let { if (it.startsWith("http")) it else base + it }
        author = this@MangaDto.author
        artist = this@MangaDto.artist
        description = this@MangaDto.description
        genre = (genres + themes).joinToString(", ")
        status = SManga.UNKNOWN
        initialized = false
    }
}

@Serializable
data class CatalogDto(
    val manga: List<MangaDto>,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class ChapterDto(
    val id: String,
    @SerialName("chapter_num") val chapterNum: String,
    val volume: String = "",
    val title: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("pages_count") val pagesCount: Int = 0,
    @SerialName("scanlation_group") val scanlationGroup: String? = null,
    val source: String? = null,
)

@Serializable
data class ChaptersDto(val chapters: List<ChapterDto>)

@Serializable
data class PagesDto(val pages: List<String>)
