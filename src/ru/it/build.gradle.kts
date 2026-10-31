import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiCity.it.com"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        baseUrl = "https://hentaicity.it.com"
        lang = "ru"
        id = 1980175137961872476L
    }
}
