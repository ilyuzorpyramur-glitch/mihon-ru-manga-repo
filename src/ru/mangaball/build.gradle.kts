import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Ball"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    // Репозиторий русский: оставлены только ru (и «весь каталог», если есть).
    listOf("ru").forEach {
        source {
            lang = it
            baseUrl = "https://mangaball.com"
        }
    }

    deeplink {
        host("mangaball.com")
        path("/title-detail/..*")
        path("/chapter-detail/..*")
    }
}
