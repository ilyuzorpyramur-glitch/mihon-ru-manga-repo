# Русские расширения манги для Mihon

Репозиторий расширений манги (Mihon / Tachiyomi) с русскими источниками.
Исходники взяты из [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)
(Apache-2.0), пересобраны и подписаны собственным ключом; иконки приведены
к единому стилю.

## Установка

В Mihon: **Настройки → Расширения → Репозитории → Добавить**, вставить:

```
https://raw.githubusercontent.com/ilyuzorpyramur-glitch/mihon-ru-manga-repo/main/index.min.json
```

Затем **Обзор → Расширения** — список появится там.

Это отдельный репозиторий от аниме: расширения Aniyomi и Mihon несовместимы
между собой, у каждого приложения свой индекс.

## Сборка

Всё собирается в GitHub Actions (`.github/workflows/build.yml`) при пуше в
`main`: gradle `assembleRelease` по всем модулям → подпись → сбор индекса
(`.github/scripts/create-repo.py`) → коммит `apk/`, `icon/`, `index.json`,
`index.min.json`.

Иконки генерируются скриптом `.github/scripts/make-icons.py` — единый стиль
задаётся там, вручную файлы не правятся.

## Лицензия

Apache License 2.0 — см. [LICENSE](LICENSE). Исходный код расширений
принадлежит проекту Keiyoushi и его контрибьюторам.
