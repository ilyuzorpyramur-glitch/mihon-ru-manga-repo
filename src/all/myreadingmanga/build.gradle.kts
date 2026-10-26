import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MyReadingManga"
    versionCode = 61
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    // Репозиторий русский: оставлены только ru (и «весь каталог», если есть).
    listOf("ru").forEach {
        source {
            lang = it
            baseUrl = "https://myreadingmanga.info"
        }
    }
}
