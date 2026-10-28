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

- 🇷🇺 **57 источников** — MangaChan, ReadManga, MintManga, Desu, MangaLib, Senkuro и другие
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
<summary><b>Мультиязычные сайты</b> (в репозитории оставлена только русская ветка)</summary>

MangaDex · MangaPlus · GlobalComix · NamiComi · XCOMIC · Niadd · NovelCool · XKCD · Manga Million
и 18+ галереи (3Hentai, HentaiEnvy, HentaiEra, IMHentai, Simply Hentai, Nude-Moon…) — у каждого источника
`lang = "ru"`, поэтому в приложении они показываются как русские и отдают русские переводы, если они есть на сайте.

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

---

## 🤖 Как я пишу код

<p align="center">
  <img src=".github/assets/ai-pair-coding.jpg" alt="AI пишет код, я проверяю" width="100%">
</p>

Не секрет: код в этих репозиториях я делаю не вручную. Основную работу — разбор
сайтов, генерацию расширений, починку селекторов и сборку — выполняет AI,
а я ставлю задачи, проверяю результат и отвечаю за то, что уходит в релиз.

- AI пишет код и готовит APK, GitHub Actions собирает и подписывает
- я проверяю источники, тестирую расширения на устройстве и решаю, что публиковать
- ошибки, регрессии и спорные решения — на мне

Так проект живёт быстрее: больше источников, чаще обновления, меньше рутины.

## ❤️ Поддержать проект

<p align="center">
  <img src=".github/assets/donate-banner.jpg" alt="Поддержать проект" width="100%">
</p>

Расширения бесплатные и без рекламы. Если они экономят твоё время —
можно отправить донат через Tribute:

<p align="center">
  <a href="https://web.tribute.tg/d/T6v"><img src="https://img.shields.io/badge/%D0%9E%D1%82%D0%BA%D1%80%D1%8B%D1%82%D1%8C%20%D0%B2%20%D0%B1%D1%80%D0%B0%D1%83%D0%B7%D0%B5%D1%80%D0%B5-Tribute-ff4d6d?style=for-the-badge&logoColor=white" alt="Поддержать в браузере"></a>
  <a href="https://t.me/tribute/app?startapp=dT6v"><img src="https://img.shields.io/badge/Telegram-Tribute-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Поддержать в Telegram"></a>
</p>

<p align="center"><sub>Спасибо, что пользуешься и поддерживаешь ❤️</sub></p>
