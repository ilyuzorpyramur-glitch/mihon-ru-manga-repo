import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Comic Fury"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    val comicFuryUrl = "https://comicfury.com"

    // Репозиторий русский: оставлены только ru (и «весь каталог», если есть).
    listOf("ru", "all").forEach {
        source {
            lang = it
            baseUrl = comicFuryUrl
        }
    }
}

dependencies {

    implementation(project(":lib:textinterceptor"))
}
