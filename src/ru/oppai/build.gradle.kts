import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "It's time for Oppai! 4K Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://oppai.stream"
        lang = "ru"
        id = 3487815453038524926L
    }
}
