import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MintManga"
    versionCode = 50
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "grouple"

    source {
        baseUrl {
            custom("https://mintmanga.one")
        }
        lang = "ru"
        id = 6L
    }
}
