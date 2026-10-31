import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentai Uncensored"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://hentaiuncensored.to"
        lang = "ru"
        id = 2477927887233105203L
    }
}
