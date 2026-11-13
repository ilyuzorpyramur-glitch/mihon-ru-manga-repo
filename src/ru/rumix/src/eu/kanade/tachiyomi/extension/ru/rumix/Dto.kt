package eu.kanade.tachiyomi.extension.ru.rumix

import kotlinx.serialization.Serializable

@Serializable
data class CatalogResponse(
    val list: List<CatalogItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class CatalogItem(
    val name: String = "",
    val picUrl: String? = null,
    val elementId: ElementId? = null,
    val issueYear: Int? = null,
    val avgRating: Double? = null,
)

@Serializable
data class ElementId(
    val linkName: String = "",
    val type: String? = null,
)
