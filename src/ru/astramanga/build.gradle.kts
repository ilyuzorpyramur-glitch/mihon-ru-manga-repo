import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "AstraManga"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        baseUrl = "https://astramanga.org"
        lang = "ru"
        id = 7938304578940592570L
    }
}
