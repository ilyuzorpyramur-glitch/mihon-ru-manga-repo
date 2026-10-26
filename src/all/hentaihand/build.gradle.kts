import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiHand"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "hentaihand"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    val languages = listOf("ru", "all")

    languages.forEach { language ->
        source {
            lang = language
            baseUrl = "https://hentaihand.com"

            if (language == "all") id = 1235047015955289468L
        }
    }
}
