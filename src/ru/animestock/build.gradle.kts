import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "AnimeStock"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://animestock.ru"
        lang = "ru"
        id = 9205683393119254565L
    }
}
