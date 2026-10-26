import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Simply Hentai"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    // Репозиторий русский: оставлены только ru (и «весь каталог», если есть).
    listOf("ru").forEach {
        source {
            lang = it
            baseUrl = "https://www.simply-hentai.com"
            versionId = 2
        }
    }
}
