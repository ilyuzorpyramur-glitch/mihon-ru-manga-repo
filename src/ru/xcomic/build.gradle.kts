import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "XCOMIC"
    versionCode = 9
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    listOf("ru").forEach {
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
