import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Tomilo-lib"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        baseUrl = "https://tomilo-lib.ru"
        lang = "ru"
        id = 7420534233267322527L
    }
}
