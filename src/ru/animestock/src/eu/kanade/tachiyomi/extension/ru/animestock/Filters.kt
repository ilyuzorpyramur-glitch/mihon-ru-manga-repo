package ru.animestock

import eu.kanade.tachiyomi.source.model.Filter

internal const val RUBRIC_ALL: Long = 0
internal const val RUBRIC_MANGA: Long = 1634
internal const val RUBRIC_HENTAI: Long = 16585
internal const val RUBRIC_COSPLAY: Long = 6448

// Манга + Хентай ищутся вместе; Косплей — только по явному выбору рубрики
internal val SEARCH_RUBRICS = longArrayOf(RUBRIC_MANGA, RUBRIC_HENTAI)

private val RUBRIC_IDS = longArrayOf(RUBRIC_ALL, RUBRIC_MANGA, RUBRIC_HENTAI, RUBRIC_COSPLAY)

/** Жанровые теги сайта: имя → (id рубрики → id тега). Тег у сайта привязан к рубрике. */
internal val SITE_TAGS: Map<String, Map<Long, Long>> =
    mapOf(
        "БДСМ" to mapOf(RUBRIC_MANGA to 8185L, RUBRIC_HENTAI to 23998L),
        "Большая грудь" to mapOf(RUBRIC_MANGA to 10788L),
        "Гарем" to mapOf(RUBRIC_MANGA to 10790L, RUBRIC_HENTAI to 24000L),
        "Гипноз" to mapOf(RUBRIC_MANGA to 10791L, RUBRIC_HENTAI to 24001L),
        "Горничные" to mapOf(RUBRIC_MANGA to 10792L, RUBRIC_HENTAI to 24002L),
        "Измена" to mapOf(RUBRIC_MANGA to 6221L, RUBRIC_HENTAI to 24008L),
        "Инцест" to mapOf(RUBRIC_HENTAI to 24011L),
        "Милф" to mapOf(RUBRIC_MANGA to 6220L, RUBRIC_HENTAI to 24004L),
        "Неко" to mapOf(RUBRIC_MANGA to 10793L),
        "Офис леди" to mapOf(RUBRIC_HENTAI to 24014L),
        "Пайзури" to mapOf(RUBRIC_MANGA to 10789L, RUBRIC_HENTAI to 24009L),
        "Первый раз" to mapOf(RUBRIC_MANGA to 5188L, RUBRIC_HENTAI to 24012L),
        "Принуждение" to mapOf(RUBRIC_MANGA to 8047L, RUBRIC_HENTAI to 24005L),
        "Тётя и племянник" to mapOf(RUBRIC_HENTAI to 24010L),
        "ФемДом" to mapOf(RUBRIC_MANGA to 13079L),
        "Футфетиш" to mapOf(RUBRIC_HENTAI to 24006L),
        "Чулки" to mapOf(RUBRIC_MANGA to 3190L),
        "Школа" to mapOf(RUBRIC_MANGA to 3189L, RUBRIC_HENTAI to 24013L),
        "Юри" to mapOf(RUBRIC_MANGA to 5189L, RUBRIC_HENTAI to 24007L),
        "Втроем" to mapOf(RUBRIC_MANGA to 10787L),
        "Втроём" to mapOf(RUBRIC_HENTAI to 23999L),
        "Зрелые" to mapOf(RUBRIC_HENTAI to 24003L),
        "FootFetish" to mapOf(RUBRIC_MANGA to 2364L),
    )

private val TAG_NAMES = listOf("Любой") + SITE_TAGS.keys.sorted()

internal class RubricFilter :
    Filter.Select<String>(
        "Рубрика",
        arrayOf("Все", "Манга", "Хентай", "Косплей"),
    ) {
    val selectedRubric: Long
        get() = RUBRIC_IDS[state]
}

internal class TagFilter :
    Filter.Select<String>(
        "Жанр",
        TAG_NAMES.toTypedArray(),
    ) {
    val selectedTag: String?
        get() = if (state == 0) null else TAG_NAMES[state]
}
