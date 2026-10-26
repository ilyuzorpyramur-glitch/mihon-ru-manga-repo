package eu.kanade.tachiyomi.extension.ru.namicomi.dto

import eu.kanade.tachiyomi.extension.ru.namicomi.NamiComiConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias ChapterListDto = PaginatedResponseDto<ChapterDataDto>

@Serializable
@SerialName(NamiComiConstants.CHAPTER)
class ChapterDataDto(override val attributes: ChapterAttributesDto? = null) : EntityDto()

@Serializable
class ChapterAttributesDto(
    val name: String?,
    val volume: String?,
    val chapter: String?,
    val pages: Int,
    val publishAt: String,
) : AttributesDto
