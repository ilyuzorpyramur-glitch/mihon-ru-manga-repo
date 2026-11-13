package eu.kanade.tachiyomi.extension.ru.namicomi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("cover_art")
class CoverArtDto(override val attributes: CoverArtAttributesDto? = null) : EntityDto()

@Serializable
class CoverArtAttributesDto(
    val fileName: String? = null,
) : AttributesDto
