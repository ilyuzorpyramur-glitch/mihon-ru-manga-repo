<h1 align="center">📚 Mihon · Русские источники манги</h1>

<p align="center">
  <a href="https://github.com/ilyuzorpyramur-glitch/mihon-ru-manga-repo/actions"><img src="https://github.com/ilyuzorpyramur-glitch/mihon-ru-manga-repo/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/keiyoushi/extensions-source"><img src="https://img.shields.io/badge/based%20on-keiyoushi-blue" alt="based on keiyoushi"></a>
  <img src="https://img.shields.io/badge/language-Русский-8ca9ff" alt="RU">
  <img src="https://img.shields.io/github/directory-file-count/ilyuzorpyramur-glitch/mihon-ru-manga-repo/src%2Fru?type=dir&label=источники" alt="sources">
</p>

> Репозиторий расширений манги для **Mihon** и его форков (Tachiyomi-совместимые читалки).
> Русские источники, собранные, подписанные и оформленные в едином стиле.

---

## ✨ Особенности

- 🇷🇺 **30+ русских источников** — MangaChan, ReadManga, MintManga, Desu, MangaLib, Senkuro и другие
- 🎨 **Единый стиль иконок** — генерируются автоматически скриптом [`make-icons.py`](.github/scripts/make-icons.py)
- 🔐 **Собственный ключ подписи** — стабильные обновления без переустановки
- ⚙️ **Настройки в каждом расширении** — выбор качества изображений, язык, лимиты запросов
- 🤖 **Полная автоматизация** — push в `main` → сборка → подпись → публикация индекса

## 📲 Установка

**Шаг 1.** Добавьте репозиторий в Mihon:

> Настройки → Расширения → Репозитории → ➕ Добавить

```
https://raw.githubusercontent.com/ilyuzorpyramur-glitch/mihon-ru-manga-repo/main/index.min.json
```

**Шаг 2.** Откройте **Обзор → Расширения** и установите нужные источники.

<details>
<summary>Совместимость с приложениями</summary>

| Приложение | Работает |
|---|:---:|
| Mihon | ✅ |
| TachiyomiSY / forks | ✅ |
| Aniyomi | ❌ (это репозиторий манги) |
| Tachiyomi (оригинал) | ❌ (мёртв) |

Для аниме используйте [aniyomi-ru-anime-repo](https://github.com/ilyuzorpyramur-glitch/aniyomi-ru-anime-repo),
для ранобэ — [lnreader-ru-plugins](https://github.com/ilyuzorpyramur-glitch/lnreader-ru-plugins).
</details>

## 📖 Каталог источников

<details>
<summary><b>Мультисорсовые темы</b> (общий код для семейств сайтов)</summary>

| Тема | Источники |
|---|---|
| `multichan` | MangaChan, YaoiChan, HentaiChan |
| `grouple` | ReadManga, MintManga, SelfManga |
| `libgroup` | MangaLib, Ranobelib (сестринские сайты) |
| `senkuro` | Senkuro, Senkognito |
| `inkstory` | InkStory |
| … | полный список в [`lib-multisrc/`](lib-multisrc) |

</details>

<details>
<summary><b>Индивидуальные источники (ru)</b></summary>

Acomics · AllHentai · AstraManga · ComX · Desu · HenChan · HentaiLib · InkStory · MangaBuff · MangaChan · MangaHub · MangaLib · MangaMen · MangaPoisk · Mangashi · MintManga · NineGrid · Nudemoon · PureManga · ReadManga · SeiManga · SelfManga · Senkognito · Senkuro · SlashLib · TomiloLib · UniComics · Usagi · Wamanga · YagamiProject · YaoiChan

</details>

<details>
<summary><b>Мультиязычные источники (all)</b></summary>

MangaDex · MangaPlus · Niadd · NovelCool · ComicFury · GlobalComix · XKCD · и другие 18+ галереи — см. [`src/all/`](src/all)

</details>

## 🔧 Сборка (для разработчиков)

Сборка идёт в GitHub Actions при пуше в `main`:

1. Gradle `assembleRelease` по всем модулям (`src/<lang>/<name>`)
2. Подпись секретным ключом (`SIGNING_KEY`, `KEY_STORE_PASSWORD`, `ALIAS`)
3. Генерация индекса — [`.github/scripts/create-repo.py`](.github/scripts/create-repo.py) собирает `index.json` / `index.min.json` из `keiyoushi-source-info.json`
4. Коммит `apk/`, `icon/`, `index*.json` обратно в репозиторий

Локально:

```bash
./gradlew :src:ru:mangachan:assembleRelease
```

Модули подключаются автоматически: достаточно создать каталог `src/ru/<имя>/`
с `build.gradle.kts` — `settings.gradle.kts` подхватит его сам.

### Формат модуля расширения

```
src/ru/<имя>/
├── build.gradle.kts      # имя, versionCode, тема, id источника
├── res/mipmap-*/         # иконки (генерируются make-icons.py)
└── src/eu/kanade/tachiyomi/extension/ru/<имя>/
    └── <Класс>.kt        # логика парсинга
```

## 🛠 Генератор расширений

Новые источники создаются автоматическим генератором
**[extension-forge](https://github.com/ilyuzorpyramur-glitch/extension-forge)**:
шаблоны расширений с иконками, настройками качества и автоинкрементом версии,
плюс ежемесячный сторож, который проверяет расширения на работоспособность.

## 📜 Лицензия

[Apache License 2.0](LICENSE). Исходный код расширений принадлежит проекту
[Keiyoushi](https://github.com/keiyoushi/extensions-source) и его контрибьюторам.

---
<p align="center">Часть экосистемы <a href="https://github.com/ilyuzorpyramur-glitch/extension-forge">extension-forge</a> · аниме · манга · ранобэ</p>
