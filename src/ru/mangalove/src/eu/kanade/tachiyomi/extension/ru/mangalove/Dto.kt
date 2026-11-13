package ru.mangalove

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Сайт отдаёт числа то строкой, то числом, а null — там, где поле объявлено
// обязательным. Эти сериализаторы терпят всю эту неоднородность.
// Сгенерировано extension-forge: правьте шаблон, а не этот файл.

object FlexibleString : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeString()
        return when (element) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

object FlexibleInt : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeInt()
        if (element is JsonNull) return 0
        return (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

object FlexibleDouble : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeDouble()
        if (element is JsonNull) return 0.0
        return (element as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    }

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
}

object FlexibleBool : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBool", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        if (element is JsonNull) return false
        val raw = (element as? JsonPrimitive)?.content ?: return false
        return raw == "true" || raw == "1"
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

object FlexibleStringList : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(FlexibleString).descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return emptyList()
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            when (item) {
                is JsonNull -> null
                is JsonPrimitive -> item.content
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        ListSerializer(FlexibleString).serialize(encoder, value)
    }
}

private class FlexibleList<T>(private val serializer: KSerializer<T>) : KSerializer<List<T>> {
    override val descriptor: SerialDescriptor = ListSerializer(serializer).descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(ListSerializer(serializer))
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonArray) return emptyList()
        return element.map { jsonDecoder.json.decodeFromJsonElement(serializer, it) }
    }

    override fun serialize(encoder: Encoder, value: List<T>) {
        ListSerializer(serializer).serialize(encoder, value)
    }
}

@Serializable
data class MangaDto(
    @Serializable(with = FlexibleString::class) val id: String = "",
    @Serializable(with = FlexibleString::class) val title: String = "",
    @SerialName("title_ru")
    @Serializable(with = FlexibleString::class)
    val titleRu: String = "",
    @SerialName("description_ru")
    @Serializable(with = FlexibleString::class)
    val descriptionRu: String = "",
    @Serializable(with = FlexibleString::class) val description: String = "",
    @SerialName("cover_url")
    @Serializable(with = FlexibleString::class)
    val coverUrl: String = "",
    @Serializable(with = FlexibleStringList::class) val genres: List<String> = emptyList(),
    @Serializable(with = FlexibleStringList::class) val themes: List<String> = emptyList(),
    @Serializable(with = FlexibleString::class) val author: String = "",
    @Serializable(with = FlexibleString::class) val artist: String = "",
    @Serializable(with = FlexibleString::class) val status: String = "",
    @Serializable(with = FlexibleInt::class) val year: Int = 0,
    @SerialName("chapters_count")
    @Serializable(with = FlexibleInt::class)
    val chaptersCount: Int = 0,
    @SerialName("rating_avg")
    @Serializable(with = FlexibleDouble::class)
    val ratingAvg: Double = 0.0,
)

object MangaList : KSerializer<List<MangaDto>> by FlexibleList(MangaDto.serializer())

@Serializable
data class CatalogDto(
    @Serializable(with = MangaList::class) val manga: List<MangaDto> = emptyList(),
    @SerialName("has_more")
    @Serializable(with = FlexibleBool::class)
    val hasMore: Boolean = false,
    @Serializable(with = FlexibleInt::class) val total: Int = 0,
)

@Serializable
data class ChapterDto(
    @Serializable(with = FlexibleString::class) val id: String = "",
    @SerialName("chapter_num")
    @Serializable(with = FlexibleString::class)
    val chapterNum: String = "",
    @Serializable(with = FlexibleString::class) val volume: String = "",
    @Serializable(with = FlexibleString::class) val title: String = "",
    @SerialName("published_at")
    @Serializable(with = FlexibleString::class)
    val publishedAt: String = "",
    @SerialName("pages_count")
    @Serializable(with = FlexibleInt::class)
    val pagesCount: Int = 0,
    @SerialName("scanlation_group")
    @Serializable(with = FlexibleString::class)
    val scanlationGroup: String = "",
)

object ChapterList : KSerializer<List<ChapterDto>> by FlexibleList(ChapterDto.serializer())

@Serializable
data class ChaptersDto(
    @Serializable(with = ChapterList::class) val chapters: List<ChapterDto> = emptyList(),
    @Serializable(with = FlexibleInt::class) val total: Int = 0,
)

@Serializable
data class PagesDto(
    @Serializable(with = FlexibleStringList::class) val pages: List<String> = emptyList(),
)
