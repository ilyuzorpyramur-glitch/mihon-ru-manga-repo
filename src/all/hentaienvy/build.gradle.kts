import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiEnvy"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "galleryadults"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    listOf("ru", "all").forEach { language ->
        source {
            lang = language
            baseUrl = "https://hentaienvy.com"
        }
    }
}
