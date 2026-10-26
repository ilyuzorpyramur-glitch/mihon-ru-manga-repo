import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Million"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    // Репозиторий русский: оставлены только ru (и «весь каталог», если есть).
    listOf("ru").forEach {
        source {
            lang = it
            baseUrl = "https://mangamillion.shueisha.co.jp"
        }
    }
}
