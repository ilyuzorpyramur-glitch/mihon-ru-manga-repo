import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Comic Fury"
    versionCode = 9
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    val comicFuryUrl = "https://comicfury.com"

    // Репозиторий русский: оставлены только ru (и «весь каталог», если есть).
    listOf("ru").forEach {
        source {
            lang = it
            baseUrl = comicFuryUrl
        }
    }
}

dependencies {

    implementation(project(":lib:textinterceptor"))
}
