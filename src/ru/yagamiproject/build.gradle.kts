import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "YagamiProject"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        baseUrl = "https://read.yagami.me"
        lang = "ru"
        id = 5458965504724393093L
    }
}
