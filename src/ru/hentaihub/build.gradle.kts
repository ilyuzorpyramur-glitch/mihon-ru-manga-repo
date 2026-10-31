import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Подтвердите возраст"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "http://v4.hentai-hub.net"
        lang = "ru"
        id = 7879967967756220144L
    }
}
