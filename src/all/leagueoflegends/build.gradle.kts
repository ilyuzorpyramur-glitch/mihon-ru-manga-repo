import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "League of Legends"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    // Репозиторий русский: оставлена только русская локаль.
    val locales = mapOf("ru" to "ru_ru")
    locales.forEach { (langCode, locale) ->
        source {
            lang = langCode
            baseUrl = "https://universe.leagueoflegends.com/$locale/comic/"
        }
    }
}
