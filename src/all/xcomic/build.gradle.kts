import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "XCOMIC"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    listOf("ru", "all").forEach {
        source {
            lang = it
            baseUrl {
                mirrors(
                    "https://xcomic.me",
                    "https://xcomic.net",
                    "https://comik.to",
                    "https://yona.to",
                )
            }
        }
    }

    deeplink {
        host("xcomic.me")
        host("xcomic.net")
        host("comik.to")
        host("yona.to")
        path("/title/..*")
        path("/source/..*")
    }
}
