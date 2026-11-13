package eu.kanade.tachiyomi.extension.ru.namicomi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias PageListDto = ResponseDto<PageListDataDto>

@Serializable
@SerialName("image_data")
class PageListDataDto(
    override val attributes: AttributesDto? = null,
    val baseUrl: String,
    val hash: String,
    val source: List<PageImageDto>,
    val low: List<PageImageDto>,
) : EntityDto()

@Serializable
class PageImageDto(
    val filename: String,
)
