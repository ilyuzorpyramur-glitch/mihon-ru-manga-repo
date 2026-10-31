import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Www"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://www.al4a.com"
        lang = "ru"
        id = 7172541215541610420L
    }
}
