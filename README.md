# HLS Grabber

> **A Windows application built on Java 21 that sniffs and downloads HLS / DASH / MP4 video streams straight from the browser.**

The app launches a managed browser, listens to its network requests and shows the sources it
finds in a list. When you press **"Download"** the stream is fetched segment by segment and
merged into a single mp4 file via FFmpeg — without re-encoding.

The principle is simple: the browser already knows how to fetch and decrypt (AES-128) the stream,
so the program does the same thing with the same URLs and keys. DRM (Widevine / PlayReady /
FairPlay) is not supported — such videos are skipped.

---

## Releases

The latest build is always in the **[Releases](https://github.com/flynet/media_downloader/releases/latest)** section.

| Version | File | Size | Date |
|---|---|---|---|
| **v1.0.0** | [**HLS-Grabber-1.0.0-windows-x64.zip**](https://github.com/flynet/media_downloader/releases/download/v1.0.0/HLS-Grabber-1.0.0-windows-x64.zip) | ~592 MB | 07.11.2026 |

SHA256 `HLS-Grabber-1.0.0-windows-x64.zip`:

```
98f6416ae5a948972063cb4db137c2edd612ae8256c33ab866a176eb064ed627
```

### How to install

1. Download `HLS-Grabber-1.0.0-windows-x64.zip`.
2. Unpack the archive into any folder (about 1.5 GB of free space is required).
3. Run **`HLS Grabber.exe`** from the `HLS Grabber` folder.
4. Pick a downloads folder — the app opens the browser together with its window.

> **SmartScreen.** The file is not signed, so Windows may show “Windows protected your PC”.
> Click **“More info” → “Run anyway”**.

> **No Java needed.** The build ships its own runtime (`jlink`) and Playwright browsers,
> so you do not need to install JDK or Chrome separately.

## Features

- **Traffic capture** via Playwright at the browser-context level — every tab and popup is visible.
- **HLS**: master and media playlists, AES-128 segments, fMP4, byte-range, iframe segments, CMAF 1080p.
- **MP4**: direct links to a video file.
- **YouTube**: stream capture through the inner-tube player API, quality selection (including 1080p).
- **Download queue**: strictly sequential execution, progress shown in the source row, cancel supported.
- **Merging** segments into a single mp4 (h264/aac) via FFmpeg — no double re-encoding.
- **Swing GUI**: two sections — found sources on top, console at the bottom.

---

## Building from Sources

> Building is only needed if you want to change the code and get your own `exe`.
> A ready exe is in **[Releases](https://github.com/flynet/media_downloader/releases/latest)** — running it
> requires installing nothing (Java is bundled in the build).

### Requirements

| Component | Version | How to install |
|---|---|---|
| JDK | 21 (`jlink` and `jpackage` are required) | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Maven | 3.9+ | no winget package: download `apache-maven-3.x-bin.zip` from [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi) and unpack, e.g. into `C:\mvn` |
| OS | Windows x64 | FFmpeg is pulled by Maven for `windows-x64` only |

Check the installed versions:

```powershell
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java" -version   # must be 21
C:\mvn\bin\mvn.cmd -version                                                       # 3.9+
```

### Paths in the script

The author's paths are hardcoded at the top of `build_appimage.bat`:

```bat
set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "MAVEN_CMD=C:\mvn\bin\mvn.cmd"
```

**Replace them with your own** — the script uses these paths literally, and on startup it
reports right away what is missing (with an install hint), before the build begins.

### Commands

```powershell
# tests and fat-jar build
mvn -f Downloader_hub\pom.xml clean package

# building the .exe (bundling browsers)
.\build_appimage.bat
```

`build_appimage.bat` builds everything in four steps:

1. `mvn clean package` → `Downloader_hub\target\hls-grabber-1.0-SNAPSHOT.jar`;
2. `jlink` → own runtime in `appimage\runtime` (~63 MB);
3. `jpackage --type app-image` → `appimage\dist\HLS Grabber\` with the `icon.ico` icon;
4. copying Playwright browsers into `app\browsers`.

JDK and Maven paths are set at the top of the script (`JDK`, `MAVEN_CMD`). The fifth step launches
the GUI; if you don't need it (e.g. when building in CI), the line can be commented out.

---

## Repository Layout

```
Downloader_hub/      Java project (Maven, single module)
  pom.xml
  src/main/java/com/example/hlsgrabber/
    browser/         browser launch and network sniffing
    download/        queue, segment download, AES-128 decryption
    ffmpeg/          FFmpeg runner, merging and mux
    hls/             playlist parsing
    manifest/        source models
    youtube/         YouTube support
    ui/              Swing GUI
  src/test/          unit and E2E tests
build_appimage.bat   Windows build script (.exe)
.github/             housekeeping hooks (OpenRewrite java-upgrade)
```

---

## License

The code is distributed “as is”. Contact the author before using it in your own products.

---

## Русский раздел

**Windows-приложение на Java 21** для перехвата и скачивания видеопотоков **HLS / DASH / MP4**
прямо из браузера. Приложение поднимает управляемый браузер, слушает его сетевые запросы и
показывает найденные источники в списке; по нажатию «Скачать» поток скачивается по сегментам
и склеивается в один mp4-файл через FFmpeg — без перекодирования. DRM
(Widevine / PlayReady / FairPlay) не поддерживается — такие видео пропускаются.

**Релизы.** Актуальная сборка — в разделе
**[Releases](https://github.com/flynet/media_downloader/releases/latest)**: **v1.0.0**,
`HLS-Grabber-1.0.0-windows-x64.zip` (~592 МБ), SHA256
`98f6416ae5a948972063cb4db137c2edd612ae8256c33ab866a176eb064ed627`.

**Установка.** Скачайте zip, распакуйте в любую папку (потребуется ~1.5 ГБ), запустите
**`HLS Grabber.exe`** из папки `HLS Grabber`, выберите папку загрузок. Файл не подписан:
если Windows покажет «Windows защитил ваш компьютер», нажмите
**«Подробнее» → «Выполнить в любом случае»**. Java не требуется — собственный рантайм
(`jlink`) и Playwright-браузеры входят в сборку.

**Возможности.** Перехват трафика через Playwright на уровне контекста (видны все вкладки и
popup-окна); HLS: master- и media-плейлисты, сегменты AES-128, fMP4, byte-range,
iframe-сегменты, CMAF 1080p; MP4 — прямые ссылки на видеофайл; YouTube — inner-tube
player API с выбором качества (включая 1080p); очередь загрузок с прогрессом и отменой;
склейка сегментов в единый mp4 (h264/aac) через FFmpeg; Swing-GUI — источники сверху,
консоль снизу.

### Сборка из исходников

Сборка нужна только если вы хотите изменить код и получить свой `exe`. Требования: JDK 21
(нужны `jlink` и `jpackage`; `winget install EclipseAdoptium.Temurin.21.JDK`), Maven 3.9+
(пакета winget нет — скачайте `apache-maven-3.x-bin.zip` с
[maven.apache.org/download.cgi](https://maven.apache.org/download.cgi)), Windows x64. Пути
к JDK/Maven прописаны в начале `build_appimage.bat` — замените их на свои. Команды:
`mvn -f Downloader_hub\pom.xml clean package` (тесты и fat-jar), `.\build_appimage.bat`
(mvn → jlink → jpackage → бандлинг браузеров; пятый шаг — запуск GUI, при сборке в CI
эту строку можно закомментировать).

**Лицензия.** Код распространяется «как есть». Перед использованием в своих продуктах
свяжитесь с автором.
