import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lunar Manga"
    versionCode = 0
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    // Репозиторий русский: оставлены только ru и all (весь каталог).
    val languages = listOf("ru", "all")

    languages.forEach { language ->
        source {
            baseUrl = "https://lunarx.to"
            lang = language
        }
    }
}

dependencies {
    implementation(project(":lib:cryptoaes"))
}
