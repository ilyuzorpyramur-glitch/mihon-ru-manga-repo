import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hentaigasm"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://hentaigasm.com"
        lang = "ru"
        id = 6934766142079007868L
    }
}
