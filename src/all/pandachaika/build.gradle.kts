import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "PandaChaika"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    listOf("ru", "all").forEach {
        source {
            lang = it
            baseUrl = "https://panda.chaika.moe"
        }
    }

    deeplink {
        host("panda.chaika.moe")
        path("/archive/..*")
    }
}
