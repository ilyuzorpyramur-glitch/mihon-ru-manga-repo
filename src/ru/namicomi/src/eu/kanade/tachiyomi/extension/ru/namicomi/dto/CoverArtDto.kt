package eu.kanade.tachiyomi.extension.ru.namicomi.dto

import eu.kanade.tachiyomi.extension.ru.namicomi.NamiComiConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName(NamiComiConstants.COVER_ART)
class CoverArtDto(override val attributes: CoverArtAttributesDto? = null) : EntityDto()

@Serializable
class CoverArtAttributesDto(
    val fileName: String? = null,
    val locale: String? = null,
) : AttributesDto
