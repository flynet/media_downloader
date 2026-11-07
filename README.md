# HLS Grabber

> Windows-приложение на Java 21 для перехвата и скачивания видеопотоков **HLS / DASH / MP4** прямо из браузера.

Приложение поднимает управляемый браузер, слушает его сетевые запросы и показывает найденные
источники в списке. По нажатию **«Скачать»** поток скачивается по сегментам и склеивается в один
mp4-файл через FFmpeg — без перекодирования.

Принцип простой: браузер уже умеет получать и расшифровывать (AES-128) поток, значит программа
делает то же самое по тем же URL и ключам. DRM (Widevine / PlayReady / FairPlay) не поддерживается —
такие видео пропускаются.

---

## Релизы

Актуальная сборка всегда лежит в разделе **[Releases](https://github.com/flynet/media_downloader/releases/latest)**.

| Версия | Файл | Размер | Дата |
|---|---|---|---|
| **v1.0.0** | [**HLS-Grabber-1.0.0-windows-x64.zip**](https://github.com/flynet/media_downloader/releases/download/v1.0.0/HLS-Grabber-1.0.0-windows-x64.zip) | ~592 МБ | 07.11.2026 |

SHA256 `HLS-Grabber-1.0.0-windows-x64.zip`:

```
98f6416ae5a948972063cb4db137c2edd612ae8256c33ab866a176eb064ed627
```

### Как установить

1. Скачайте `HLS-Grabber-1.0.0-windows-x64.zip`.
2. Распакуйте архив в любую папку (потребуется ~1.5 ГБ свободного места).
3. Запустите **`HLS Grabber.exe`** из папки `HLS Grabber`.
4. Выберите папку для загрузок — приложение откроет браузер с окном программы.

> **SmartScreen.** Файл не подписан, поэтому Windows может показать «Windows защитил ваш компьютер».
> Нажмите **«Подробнее» → «Выполнить в любом случае»**.

> **Java не требуется.** В сборку входит собственный рантайм (`jlink`) и Playwright-браузеры,
> поэтому устанавливать JDK или Chrome отдельно не нужно.

---

## Возможности

- **Перехват трафика** через Playwright на уровне контекста — видны все вкладки и popup-окна.
- **HLS**: master- и media-плейлисты, сегменты AES-128, fMP4, byte-range, iframe-сегменты, CMAF 1080p.
- **MP4**: прямые ссылки на видеофайл.
- **YouTube**: перехват потока через inner-tube player API, выбор качества (в том числе 1080p).
- **Очередь загрузок**: строго последовательное выполнение, прогресс в строке источника, отмена.
- **Склейка** сегментов в единый mp4 (h264/aac) через FFmpeg — без двойного перекодирования.
- **GUI на Swing**: две секции — найденные источники сверху, консоль снизу.

---

## Сборка из исходников

### Требования

| Компонент | Версия |
|---|---|
| JDK | 21 (Temurin / OpenJDK), используется `jlink` и `jpackage` |
| Maven | 3.9+ |
| ОС | Windows x64 (FFmpeg подтягивается только под `windows-x86_64`) |

### Команды

```powershell
# тесты и сборка fat-jar
mvn -f Downloader_hub\pom.xml clean package

# сборка .exe (Maven -> jlink -> jpackage -> бандлинг браузеров)
.\build_appimage.bat
```

`build_appimage.bat` собирает всё в четыре шага:

1. `mvn clean package` → `Downloader_hub\target\hls-grabber-1.0-SNAPSHOT.jar`;
2. `jlink` → собственный рантайм в `appimage\runtime` (~63 МБ);
3. `jpackage --type app-image` → `appimage\dist\HLS Grabber\` с иконкой `icon.ico`;
4. копирование Playwright-браузеров в `app\browsers`.

Пути к JDK и Maven задаются в начале скрипта (`JDK`, `MAVEN_CMD`). Пятый шаг — запуск GUI;
если он не нужен (например, при сборке в CI), эту строку можно закомментировать.

---

## Структура репозитория

```
Downloader_hub/      Java-проект (Maven, один модуль)
  pom.xml
  src/main/java/com/example/hlsgrabber/
    browser/         запуск браузера и перехват сетевых запросов
    download/        очередь, скачивание сегментов, расшифровка AES-128
    ffmpeg/          запуск FFmpeg, склейка и mux
    hls/             разбор плейлистов
    manifest/        модели источников
    youtube/         поддержка YouTube
    ui/              Swing-GUI
  src/test/          unit- и E2E-тесты
  src/docs/          ТЗ, план и статус проекта
build_appimage.bat   сборка Windows-версии (.exe)
.github/             служебные хуки (OpenRewrite java-upgrade)
```

---

## Документация проекта

- [`src/docs/TZ_HLS_Grabber.md`](Downloader_hub/src/docs/TZ_HLS_Grabber.md) — техническое задание (v4).
- [`src/docs/PLAN_HLS_Grabber.md`](Downloader_hub/src/docs/PLAN_HLS_Grabber.md) — план работ по этапам.
- [`src/docs/STATUS_HLS_Grabber.md`](Downloader_hub/src/docs/STATUS_HLS_Grabber.md) — текущее состояние.

---

## Лицензия

Код распространяется «как есть». Перед использованием в своих продуктах свяжитесь с автором.
