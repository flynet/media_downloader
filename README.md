# HLS Grabber

> **RU:** Windows-приложение на Java 21 для перехвата и скачивания видеопотоков **HLS / DASH / MP4** прямо из браузера.
> **EN:** A Windows application built on Java 21 that sniffs and downloads **HLS / DASH / MP4** video streams straight from the browser.

Приложение поднимает управляемый браузер, слушает его сетевые запросы и показывает найденные
источники в списке. По нажатию **«Скачать»** поток скачивается по сегментам и склеивается в один
mp4-файл через FFmpeg — без перекодирования.

The app launches a managed browser, listens to its network requests and shows the sources it
finds in a list. When you press **«Download»** the stream is fetched segment by segment and
merged into a single mp4 file via FFmpeg — without re-encoding.

Принцип простой: браузер уже умеет получать и расшифровывать (AES-128) поток, значит программа
делает то же самое по тем же URL и ключам. DRM (Widevine / PlayReady / FairPlay) не поддерживается —
такие видео пропускаются.

The principle is simple: the browser already knows how to fetch and decrypt (AES-128) the stream,
so the program does the same thing with the same URLs and keys. DRM (Widevine / PlayReady /
FairPlay) is not supported — such videos are skipped.

---

## Релизы / Releases

Актуальная сборка всегда лежит в разделе **[Releases](https://github.com/flynet/media_downloader/releases/latest)**.

The latest build is always in the **[Releases](https://github.com/flynet/media_downloader/releases/latest)** section.

| Версия / Version | Файл / File | Размер / Size | Дата / Date |
|---|---|---|---|
| **v1.0.0** | [**HLS-Grabber-1.0.0-windows-x64.zip**](https://github.com/flynet/media_downloader/releases/download/v1.0.0/HLS-Grabber-1.0.0-windows-x64.zip) | ~592 МБ / MB | 07.11.2026 |

SHA256 `HLS-Grabber-1.0.0-windows-x64.zip`:

```
98f6416ae5a948972063cb4db137c2edd612ae8256c33ab866a176eb064ed627
```

### Как установить / How to install

1. Скачайте `HLS-Grabber-1.0.0-windows-x64.zip`.
2. Распакуйте архив в любую папку (потребуется ~1.5 ГБ свободного места).
3. Запустите **`HLS Grabber.exe`** из папки `HLS Grabber`.
4. Выберите папку для загрузок — приложение откроет браузер с окном программы.

1. Download `HLS-Grabber-1.0.0-windows-x64.zip`.
2. Unpack the archive into any folder (about 1.5 GB of free space is required).
3. Run **`HLS Grabber.exe`** from the `HLS Grabber` folder.
4. Pick a downloads folder — the app opens the browser together with its window.

> **SmartScreen (RU).** Файл не подписан, поэтому Windows может показать «Windows защитил ваш компьютер».
> Нажмите **«Подробнее» → «Выполнить в любом случае»**.
>
> **SmartScreen (EN).** The file is not signed, so Windows may show “Windows protected your PC”.
> Click **“More info” → “Run anyway”**.

> **Java не требуется (RU).** В сборку входит собственный рантайм (`jlink`) и Playwright-браузеры,
> поэтому устанавливать JDK или Chrome отдельно не нужно.
>
> **No Java needed (EN).** The build ships its own runtime (`jlink`) and Playwright browsers,
> so you do not need to install JDK or Chrome separately.

---

## Возможности / Features

- **Перехват трафика** через Playwright на уровне контекста — видны все вкладки и popup-окна.
  **Traffic capture** via Playwright at the browser-context level — every tab and popup is visible.
- **HLS**: master- и media-плейлисты, сегменты AES-128, fMP4, byte-range, iframe-сегменты, CMAF 1080p.
  **HLS**: master and media playlists, AES-128 segments, fMP4, byte-range, iframe segments, CMAF 1080p.
- **MP4**: прямые ссылки на видеофайл.
  **MP4**: direct links to a video file.
- **YouTube**: перехват потока через inner-tube player API, выбор качества (в том числе 1080p).
  **YouTube**: stream capture through the inner-tube player API, quality selection (including 1080p).
- **Очередь загрузок**: строго последовательное выполнение, прогресс в строке источника, отмена.
  **Download queue**: strictly sequential execution, progress shown in the source row, cancel supported.
- **Склейка** сегментов в единый mp4 (h264/aac) через FFmpeg — без двойного перекодирования.
  **Merging** segments into a single mp4 (h264/aac) via FFmpeg — no double re-encoding.
- **GUI на Swing**: две секции — найденные источники сверху, консоль снизу.
  **Swing GUI**: two sections — found sources on top, console at the bottom.

---

## Сборка из исходников / Building from Sources

> Сборка нужна только если вы хотите изменить код и получить свой `exe`.
> Готовый exe лежит в **[Releases](https://github.com/flynet/media_downloader/releases/latest)** — для запуска
> ничего устанавливать не нужно (Java входит в сборку).

> Building is only needed if you want to change the code and get your own `exe`.
> A ready exe is in **[Releases](https://github.com/flynet/media_downloader/releases/latest)** — running it
> requires installing nothing (Java is bundled in the build).

### Требования / Requirements

| Компонент / Component | Версия / Version | Как установить / How to install |
|---|---|---|
| JDK | 21 (нужны `jlink` и `jpackage` / `jlink` and `jpackage` are required) | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Maven | 3.9+ | нет пакета winget: скачайте `apache-maven-3.x-bin.zip` с [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi) и распакуйте, например в `C:\mvn` / no winget package: download `apache-maven-3.x-bin.zip` from [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi) and unpack, e.g. into `C:\mvn` |
| ОС / OS | Windows x64 | FFmpeg подтягивается Maven-ом только под `windows-x86_64` / FFmpeg is pulled by Maven for `windows-x86_64` only |

Проверка установленных версий / Check the installed versions:

```powershell
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java" -version   # должна быть 21 / must be 21
C:\mvn\bin\mvn.cmd -version                                                       # 3.9+
```

### Пути в скрипте / Paths in the script

В начале `build_appimage.bat` прописаны пути автора:

The author's paths are hardcoded at the top of `build_appimage.bat`:

```bat
set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "MAVEN_CMD=C:\mvn\bin\mvn.cmd"
```

**Замените их на свои** — на пути из этих строк скрипт ссылается буквально, и при запуске
сразу сообщает, чего не хватает (сообщение с подсказкой установки), ещё до начала сборки.

**Replace them with your own** — the script uses these paths literally, and on startup it
reports right away what is missing (with an install hint), before the build begins.

### Команды / Commands

```powershell
# тесты и сборка fat-jar / tests and fat-jar build
mvn -f Downloader_hub\pom.xml clean package

# сборка .exe (Maven -> jlink -> jpackage -> бандлинг браузеров) / building the .exe (bundling browsers)
.\build_appimage.bat
```

`build_appimage.bat` собирает всё в четыре шага / builds everything in four steps:

1. `mvn clean package` → `Downloader_hub\target\hls-grabber-1.0-SNAPSHOT.jar`;
2. `jlink` → собственный рантайм в `appimage\runtime` (~63 МБ) / own runtime in `appimage\runtime` (~63 MB);
3. `jpackage --type app-image` → `appimage\dist\HLS Grabber\` с иконкой `icon.ico` / with the `icon.ico` icon;
4. копирование Playwright-браузеров в `app\browsers` / copying Playwright browsers into `app\browsers`.

Пути к JDK и Maven задаются в начале скрипта (`JDK`, `MAVEN_CMD`). Пятый шаг — запуск GUI;
если он не нужен (например, при сборке в CI), эту строку можно закомментировать.

JDK and Maven paths are set at the top of the script (`JDK`, `MAVEN_CMD`). The fifth step launches
the GUI; if you don't need it (e.g. when building in CI), the line can be commented out.

---

## Структура репозитория / Repository Layout

```
Downloader_hub/      Java-проект (Maven, один модуль) / Java project (Maven, single module)
  pom.xml
  src/main/java/com/example/hlsgrabber/
    browser/         запуск браузера и перехват сетевых запросов / browser launch and network sniffing
    download/        очередь, скачивание сегментов, расшифровка AES-128 / queue, segment download, AES-128 decryption
    ffmpeg/          запуск FFmpeg, склейка и mux / FFmpeg runner, merging and mux
    hls/             разбор плейлистов / playlist parsing
    manifest/        модели источников / source models
    youtube/         поддержка YouTube / YouTube support
    ui/              Swing-GUI / Swing GUI
  src/test/          unit- и E2E-тесты / unit and E2E tests
build_appimage.bat   сборка Windows-версии (.exe) / Windows build script (.exe)
.github/             служебные хуки (OpenRewrite java-upgrade) / housekeeping hooks (OpenRewrite java-upgrade)
```

---

## Лицензия / License

Код распространяется «как есть». Перед использованием в своих продуктах свяжитесь с автором.

The code is distributed “as is”. Contact the author before using it in your own products.
