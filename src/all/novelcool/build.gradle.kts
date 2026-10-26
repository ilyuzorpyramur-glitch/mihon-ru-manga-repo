import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NovelCool"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    // Репозиторий русский: оставлен только русский поддомен.
    val subdomains = mapOf("ru" to "ru")
    subdomains.forEach { (langCode, sub) ->
        source {
            lang = langCode
            baseUrl = "https://$sub.novelcool.com"
        }
    }
}
