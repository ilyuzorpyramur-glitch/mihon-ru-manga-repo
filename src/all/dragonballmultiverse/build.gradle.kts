import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Dragon Ball Multiverse"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    val dbmUrl = "https://www.dragonball-multiverse.com"

    // Репозиторий русский: оставлен только ru.
    listOf("ru").forEach {
        source {
            lang = it
            baseUrl = dbmUrl
        }
    }
}
