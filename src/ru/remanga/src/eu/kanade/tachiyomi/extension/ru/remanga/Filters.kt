package ru.remanga

import eu.kanade.tachiyomi.source.model.Filter

internal class OrderBy :
    Filter.Sort(
        "Сортировка",
        arrayOf("Новизне", "Последним обновлениям", "Популярности", "Лайкам", "Просмотрам", "По кол-ву глав", "Мне повезёт"),
        Selection(2, false),
    ) {
    val ordering: String
        get() {
            val base = ORDERS[state?.index ?: 2]
            return if (state?.ascending == true) base else "-$base"
        }

    private companion object {
        val ORDERS = arrayOf("id", "chapter_date", "rating", "votes", "views", "count_chapters", "random")
    }
}

internal class GenreFilter(
    name: String,
    val id: String,
) : Filter.TriState(name)

internal class GenreGroup(
    genres: List<Pair<String, String>>,
) : Filter.Group<GenreFilter>("Жанры", genres.map { GenreFilter(it.first, it.second) })

internal class CategoryGroup(
    categories: List<Pair<String, String>>,
) : Filter.Group<GenreFilter>("Категории", categories.map { GenreFilter(it.first, it.second) })

internal class TypeGroup(
    types: List<Pair<String, String>>,
) : Filter.Group<GenreFilter>("Тип", types.map { GenreFilter(it.first, it.second) })

internal class StatusFilter(
    name: String,
    val id: String,
) : Filter.CheckBox(name)

internal class StatusGroup : Filter.Group<StatusFilter>("Статус", STATUSES.map { StatusFilter(it.first, it.second) })

internal class AgeGroup : Filter.Group<StatusFilter>("Возрастное ограничение", AGES.map { StatusFilter(it.first, it.second) })

private val STATUSES =
    listOf(
        "Закончен" to "0",
        "Продолжается" to "1",
        "Заморожен" to "2",
        "Нет переводчика" to "3",
        "Анонс" to "4",
        "Лицензировано" to "5",
    )

private val AGES =
    listOf(
        "Для всех" to "0",
        "16+" to "1",
        "18+" to "2",
    )

internal val GENRES =
    listOf(
        "боевые искусства" to "3",
        "гарем" to "5",
        "гендерная интрига" to "6",
        "героическое фэнтези" to "7",
        "детектив" to "8",
        "дзёсэй" to "9",
        "додзинси" to "10",
        "драма" to "11",
        "история" to "13",
        "киберпанк" to "14",
        "кодомо" to "15",
        "комедия" to "50",
        "махо-сёдзё" to "17",
        "меха" to "18",
        "мистика" to "19",
        "мурим" to "51",
        "научная фантастика" to "20",
        "повседневность" to "21",
        "постапокалиптика" to "22",
        "приключения" to "23",
        "психология" to "24",
        "психодел-упоротость-треш" to "124",
        "романтика" to "25",
        "сверхъестественное" to "27",
        "сёдзё" to "28",
        "сёдзё-ай" to "29",
        "сёнэн" to "30",
        "сёнэн-ай" to "31",
        "спорт" to "32",
        "сэйнэн" to "33",
        "трагедия" to "34",
        "триллер" to "35",
        "ужасы" to "36",
        "фантастика" to "37",
        "фэнтези" to "38",
        "школьная жизнь" to "39",
        "экшен" to "2",
        "элементы юмора" to "16",
        "эротика" to "42",
        "этти" to "40",
        "юри" to "41",
    )

internal val CATEGORIES =
    listOf(
        "веб" to "5",
        "в цвете" to "6",
        "ёнкома" to "8",
        "сборник" to "10",
        "сингл" to "11",
        "ангелы" to "48",
        "антигерой" to "26",
        "антиутопия" to "49",
        "апокалипсис" to "50",
        "аристократия" to "117",
        "армия" to "51",
        "артефакты" to "52",
        "амнезия" to "123",
        "боги" to "45",
        "борьба за власть" to "53",
        "будущее" to "55",
        "бои на мечах" to "122",
        "вампиры" to "112",
        "вестерн" to "56",
        "видеоигры" to "35",
        "виртуальная реальность" to "44",
        "владыка демонов" to "57",
        "военные" to "29",
        "волшебные существа" to "59",
        "воспоминания из другого мира" to "60",
        "врачи" to "116",
        "выживание" to "41",
        "горничные" to "23",
        "гяру" to "28",
        "гг женщина" to "63",
        "гг мужчина" to "64",
        "умный гг" to "111",
        "тупой гг" to "109",
        "гг имба" to "110",
        "гг не человек" to "123",
        "грузовик-сан" to "125",
        "геймеры" to "61",
        "гильдии" to "62",
        "гоблины" to "65",
        "девушки-монстры" to "37",
        "демоны" to "15",
        "драконы" to "66",
        "дружба" to "67",
        "жестокий мир" to "69",
        "животные компаньоны" to "70",
        "завоевание мира" to "71",
        "зверолюди" to "19",
        "зомби" to "14",
        "игровые элементы" to "73",
        "исекай" to "115",
        "квесты" to "75",
        "космос" to "76",
        "кулинария" to "16",
        "культивация" to "18",
        "лоли" to "108",
        "магическая академия" to "78",
        "магия" to "22",
        "мафия" to "24",
        "медицина" to "17",
        "месть" to "79",
        "монстры" to "38",
        "музыка" to "39",
        "навыки и способности" to "80",
        "наёмники" to "81",
        "насилие" to "82",
        "нежить" to "83",
        "ниндзя" to "30",
        "офисные работники" to "40",
        "оборотни" to "113",
        "пародия" to "85",
        "подземелья" to "86",
        "политика" to "87",
        "полиция" to "32",
        "преступники" to "36",
        "призраки и духи" to "27",
        "прокачка" to "118",
        "путешествия во времени" to "43",
        "разумные расы" to "88",
        "ранги силы" to "68",
        "реинкарнация" to "13",
        "роботы" to "89",
        "рыцари" to "90",
        "средневековье" to "25",
        "самураи" to "33",
        "система" to "91",
        "скрытие личности" to "93",
        "спасение мира" to "94",
        "стимпанк" to "92",
        "супергерои" to "95",
        "традиционные игры" to "34",
        "учитель и ученик" to "96",
        "управление территорией" to "114",
        "философия" to "97",
        "хентай" to "12",
        "хикикомори" to "21",
        "шантаж" to "99",
        "эльфы" to "46",
    )

internal val TYPES =
    listOf(
        "Манга" to "0",
        "Манхва" to "1",
        "Маньхуа" to "2",
        "Западный комикс" to "3",
        "Рукомикс" to "4",
        "Индонезийский комикс" to "5",
        "Другое" to "6",
    )
