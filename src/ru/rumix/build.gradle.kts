import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "RuMix"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "grouple"

    source {
        lang = "ru"
        baseUrl = "https://rumix.me"
    }

    deeplink {
        host("rumix.me")
        path("/..*")
    }
}
