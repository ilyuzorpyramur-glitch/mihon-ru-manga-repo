import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "xkcd"
    versionCode = 17
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    // Репозиторий русский: оставлено только русское зеркало.
    source {
        lang = "ru"
        baseUrl = "https://xkcd.ru"
    }
}

dependencies {
    implementation(project(":lib:textinterceptor"))
}
