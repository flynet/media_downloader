# HLS Grabber

Self-contained Windows app for HLS/DASH downloads.
Open an m3u8/mpd, choose segments, save MP4/MKV without re-encoding.

![HLS Grabber screenshot](https://github.com/user-attachments/assets/f83134e2-5473-4a5d-b671-16671500d48d)

## Releases

| File | What it is | Date | SHA256 |
| --- | --- | --- | --- |
| `HLS-Grabber-1.0.0-windows-x64.zip` | Full Windows build: app + bundled runtime (no Java install required). | 08.11.2026 | `34156015…aafc` |
| `HLS-Grabber-1.0.0.jar` | Bare JAR (requires Java 21). | 05.11.2026 | `9d223958…24b1` |

Download from [Releases](https://github.com/flynet/media_downloader/releases).

Full SHA256 values:

```
sha256: 34156015a965b7422970a84f0b37a7107f2d1a8ea1edfbe778151b3bae42aafc
sha256: 9d223958155491193932434564944456479599646611723939312449338124b1
```

### How to install

1. Unpack zip to any folder.
2. Run `HLS Grabber.exe` — no installation required.
3. SmartScreen (Windows): first launch of an unsigned exe can be blocked («Защитите ПК…»). Use **More info** → **Run anyway**.
   > The «Защитите ПК» dialog is standard Windows behavior for unsigned exe files. The app installs nothing and needs no admin rights.
4. If `HLS Grabber.exe` fails to start (a log in `app.log` reports a missing runtime):
   - Install [Java 21](https://adoptium.net/temurin/releases/?version=21)
   - Put `HLS-Grabber-1.0.0.jar` in the same folder as `HLS Grabber.exe`
   - Run `HLS Grabber.exe` again — the app finds system Java and starts
   > Do not place `HLS-Grabber-1.0.0.jar` in the app folder without `HLS Grabber.exe` — the JAR is not double-clickable, use `java -jar`.

## Features

- HLS (.m3u8) and DASH (.mpd) streams
- Parallel segment downloads, configurable worker count
- MP4/MKV muxing without re-encode
- Live progress bar and log
- Playlists and batch downloads
- Media MD5 export
- 30 UI languages
- Bundled Chromium for URL capture
- 4K UHD + HDR10, HEVC

## Building from Sources

### Requirements

| Tool | Notes |
| --- | --- |
| JDK 21.0.x | Hotspot is the shortest path |
| Maven | 3.8+ |
| Windows | `jpackage` needs Wix Toolset MSI installed first |
| MSYS2 | Only for AppImage builds |

### Paths in the script

`build_appimage.bat` contains developer-specific paths:

```bat
set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "MSYS=C:\msys64"
set "MVN=C:\maven\apache-maven-3.9.12\bin\mvn.cmd"
set "PGBALL=C:\Program Files\Playwright Java\browsers"
```

Change these lines to your machine's paths before running the build.

### Commands

```powershell
# Verification (54 tests)
mvn -q -DskipTests=false test

# Full AppImage build: Maven jar + jlink + jpackage + Playwright browsers
cmd /c build_appimage.bat
```

The script builds the Maven jar, creates a minimal Java runtime with `jlink`, then assembles a self-contained app with `jpackage`. Playwright browsers (`chromium-1248`, `chromium_headless_shell-1248`, `ffmpeg-1013`, `winldd-1007`) are copied into `app/app/browsers`.

**Steps**

1. Change paths (JDK / MSYS / Maven / Playwright browsers)
2. Run `cmd /c build_appimage.bat`
3. The script writes `appimage\dist\HLS Grabber\`

## Repository Layout

```
HLS Grabber/
├─ build_appimage.bat           jpackage AppImage build (Windows)
├─ .gitignore
├─ bin/
│  ├─ HlsGrabber              Linux launcher script
│  └─ com.flynet.hlsgrabber.App  launcher metadata
├─ src/main/java/com/flynet/hlsgrabber/
│  ├─ App.java                Swing UI
│  ├─ I18n.java             30 languages
│  ├─ AppLogger.java        app.log
│  ├─ UrlResolver.java      Playwright URL capture
│  ├─ DownloadWorker.java   parallel segments
│  ├─ MpdParser.java        DASH .mpd
│  └─ M3u8Parser.java       HLS .m3u8
└─ src/test/java/com/flynet/hlsgrabber/
   ├─ AppTest.java
   └─ DownloadWorkerTest.java
```

## License

- MIT
- Arbitration court: Russian Federation
- Applicable law: Russian Federation

Full text in [LICENSE](LICENSE).

---

## Русский раздел

### Загрузки

| Файл | Что это | Дата | SHA256 |
| --- | --- | --- | --- |
| `HLS-Grabber-1.0.0-windows-x64.zip` | Полная Windows-сборка: приложение + встроенный runtime (Java не нужна). | 08.11.2026 | `34156015…aafc` |
| `HLS-Grabber-1.0.0.jar` | Bare JAR (нужна Java 21). | 05.11.2026 | `9d223958…24b1` |

Скачивать из [Releases](https://github.com/flynet/media_downloader/releases).

Полные значения SHA256:

```
sha256: 34156015a965b7422970a84f0b37a7107f2d1a8ea1edfbe778151b3bae42aafc
sha256: 9d223958155491193932434564944456479599646611723939312449338124b1
```

### Установка

1. Распаковать zip в любую папку.
2. Запустить `HLS Grabber.exe` — установка не требуется.
3. SmartScreen (Windows): первый запуск неподписанного exe может быть заблокирован («Защитите ПК…»). Использовать **Подробнее** → **Всё равно**.
   > Окно «Защитите ПК» — стандартное поведение Windows для неподписанных exe. Приложение ничего не устанавливает и не требует прав администратора.
4. Если `HLS Grabber.exe` не запускается (в `app.log` — сообщение о отсутствии runtime):
   - Установить [Java 21](https://adoptium.net/temurin/releases/?version=21)
   - Положить `HLS-Grabber-1.0.0.jar` в ту же папку, что и `HLS Grabber.exe`
   - Запустить `HLS Grabber.exe` повторно — приложение найдёт системную Java и запустится
   > Не помещать `HLS-Grabber-1.0.0.jar` в папку приложения без `HLS Grabber.exe` — JAR не запускается двойным кликом, использовать `java -jar`.

### Возможности

- HLS (.m3u8) и DASH (.mpd) стримы
- Параллельное скачивание сегментов, настраиваемое число потоков
- Склейка MP4/MKV без перекодирования
- Прогресс-бар и журнал в реальном времени
- Плейлисты и пакетные скачивания
- Экспорт Media MD5
- 30 языков интерфейса
- Встроенный Chromium для URL-захвата
- 4K UHD + HDR10, HEVC

### Сборка из исходников

#### Требования

| Инструмент | Примечание |
| --- | --- |
| JDK 21.0.x | Hotspot — самый короткий путь |
| Maven | 3.8+ |
| Windows | `jpackage` требует установленного Wix Toolset MSI |
| MSYS2 | Только для AppImage-сборки |

#### Пути в скрипте

`build_appimage.bat` содержит пути, специфичные для машины разработчика:

```bat
set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "MSYS=C:\msys64"
set "MVN=C:\maven\apache-maven-3.9.12\bin\mvn.cmd"
set "PGBALL=C:\Program Files\Playwright Java\browsers"
```

Замените эти строки на пути своей машины перед запуском сборки.

#### Команды

```powershell
# Проверка (54 теста)
mvn -q -DskipTests=false test

# Полная AppImage-сборка: Maven jar + jlink + jpackage + Playwright browsers
cmd /c build_appimage.bat
```

Скрипт собирает Maven jar, создаёт минимальный Java runtime через `jlink`, затем собирает self-contained приложение через `jpackage`. Playwright browsers (`chromium-1248`, `chromium_headless_shell-1248`, `ffmpeg-1013`, `winldd-1007`) копируются в `app/app/browsers`.

**Шаги**

1. Изменить пути (JDK / MSYS / Maven / Playwright browsers)
2. Запустить `cmd /c build_appimage.bat`
3. Скрипт создаёт `appimage\dist\HLS Grabber\`

### Структура репозитория

```
HLS Grabber/
├─ build_appimage.bat           jpackage AppImage build (Windows)
├─ .gitignore
├─ bin/
│  ├─ HlsGrabber              Linux launcher script
│  └─ com.flynet.hlsgrabber.App  launcher metadata
├─ src/main/java/com/flynet/hlsgrabber/
│  ├─ App.java                Swing UI
│  ├─ I18n.java             30 языков
│  ├─ AppLogger.java        app.log
│  ├─ UrlResolver.java      Playwright URL capture
│  ├─ DownloadWorker.java   параллельные сегменты
│  ├─ MpdParser.java        DASH .mpd
│  └─ M3u8Parser.java       HLS .m3u8
└─ src/test/java/com/flynet/hlsgrabber/
   ├─ AppTest.java
   └─ DownloadWorkerTest.java
```

### Лицензия

- MIT
- Третейский суд: Российская Федерация
- Применимое право: российское законодательство

Полный текст в [LICENSE](LICENSE).
