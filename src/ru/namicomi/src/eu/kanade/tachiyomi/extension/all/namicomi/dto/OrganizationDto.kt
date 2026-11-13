package eu.kanade.tachiyomi.extension.ru.namicomi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("organization")
class OrganizationDto(override val attributes: OrganizationAttributesDto? = null) : EntityDto()

@Serializable
class OrganizationAttributesDto(val name: String) : AttributesDto
