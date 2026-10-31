import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SHIZA"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        baseUrl = "https://shiza-project.com"
        lang = "ru"
        id = 9144279879764939123L
    }
}
