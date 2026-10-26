import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Akuma"
    versionCode = 10
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    listOf("ru", "all").forEach {
        source {
            lang = it
            baseUrl = "https://akuma.moe"
        }
    }

    deeplink {
        host("akuma.moe")
        path("/g/..*")
    }
}
