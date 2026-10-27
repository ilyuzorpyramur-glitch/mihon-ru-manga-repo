import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Remanga"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        baseUrl = "https://remanga.org"
        lang = "ru"
        id = 8983242087533137528L
    }
}
