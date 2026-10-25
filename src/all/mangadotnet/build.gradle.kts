import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaDot"
    versionCode = 23
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    pkgName = "en.mangadotnet"

    // Репозиторий русский: оставлен только ru (id сохранён, чтобы не
    // потерять библиотеку у уже установленного источника).
    val oldIds = listOf("ru" to 8911989140118399619L)

    oldIds.forEach { (langCode, oldId) ->
        source {
            lang = langCode
            baseUrl = "https://mangadot.net"
            id = oldId
        }
    }

    deeplink {
        host("mangadot.net")
        path("/manga/..*")
        path("/chapter/..*")
        path("/volume/..*")
    }
}
