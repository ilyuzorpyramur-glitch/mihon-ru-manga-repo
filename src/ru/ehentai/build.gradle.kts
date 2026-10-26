import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "ru"
        baseUrl = "https://e-hentai.org"
    }

    deeplink {
        host("e-hentai.org")
        path("/g/..*")
    }
}
