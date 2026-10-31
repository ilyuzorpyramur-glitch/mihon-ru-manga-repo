import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HD Hentai Video Streaming"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://www.hentaicloud.com"
        lang = "ru"
        id = 2142720584991317292L
    }
}
