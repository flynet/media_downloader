# HLS Grabber — статус проекта

Обновлено: 2026-11-07 (Этап 7 app-image — PASS, приёмка пользователем; фикс склейки TS). ТЗ: `src/docs/TZ_HLS_Grabber.md` (v4). План: `src/docs/PLAN_HLS_Grabber.md`.

## Где мы

- **Phase 0 (спайки R1–R7)** — закрыт: все риск-эксперименты проведены, сводный отчёт
  `src/docs/spikes/R3-R7-report.md`, трек-таблица плана обновлена. R8 (упаковка) — условно, этап 8.
- **Этап 1 (Stage 1: ядро)** — закрыт: компилируется, тесты 30/30 зелёные, shaded jar собирается.
- **Этап 2 (браузер + sniffing + GUI)** — все классы записаны, компилируются. Сквозной
  E2E-харнесс — **PASS (прогон 3, 2026-11-05)**: headed Chrome (channel chrome) + sniffing
  + AES-128 HLS 15/15 сегментов + ffmpeg-склейка → mp4 13.7 MB (h264/aac, ffprobe проверен).
  **GUI-приёмка лично пользователем — пройдена** (2026-11-06): окно, список источников,
  «Скачать», прогресс, консоль — подтверждены на реальных потоках.
- **Этапы 3–6 — закрыты**: матрица фильтров, HLS-фичи (AES-128, fMP4, byterange, iframe, popup,
  CMAF 1080p), очередь/отмена/закрытие. Харнессы E2e/Repro4/CmafRepro/Repro6 — PASS.
- **YouTube — добавлен по запросу пользователя (2026-11-06)**, GUI-приёмка подтверждена.
- **Выбор качества (2026-11-07)**: YouTube и HLS показывают доступные высоты, селектор позволяет выбрать конкретное качество или «Лучшее доступное»; auto выбирает максимум.
- **Этап 5 (DASH) — не начат** (по анкете 2026-11-05: DASH последним).
- **Этап 7 (jpackage app-image без Java) — закрыт (2026-11-07):** `build_appimage.bat` (очистка →
  `mvn clean package` → jlink-рантайм 62.7 MB → `jpackage --type app-image` с иконкой из
  `src\main\resources\icon.ico` → бандлинг Playwright-браузеров в `app\browsers` → запуск GUI).
  app-image 718 MB, exe 1 209 856 B, GUI-приёмка пользователем — PASS (стенд 8123 → mp4).
- **Дефект склейки TS — найден и исправлен (2026-11-07):** см. раздел «Склейка сегментов».
- **Этап 8 (приёмка раздела 9 на тестовых сайтах) — ожидаются сайты/аккаунты от пользователя.**

## Готово

### Phase 0 — спайки (все ✅, кроме R8 условного)
- R1/R2: Playwright sniffing + replay fidelity (Java, Playwright 1.64.0).
- R3: system Chrome, headless, anti-detect, CDP Web-Transport — рабочий путь подтверждён.
- R4: ffmpeg 8.1.2 bytedeco (`windows-x86_64`), decrypt + concat + playable.
- R5/R6/R7: hls.js adapter, replay fidelity, popup, anti-detect.
- Харнесс: `D:\Java\Downloader\spikes\r1-r2\` — `key.bin`, `key_info.txt`, `out.m3u8`, `out0..out14.ts`,
  `gen/`, `key-test2/`, `static/` (index.html, hls.min.js, hls-play.html, popup.html, iframe.html,
  popup-marker.bin), `SpikeMain.java`, скомпилированные классы.
  **Java HttpServer 127.0.0.1:8123 нужно собрать заново** — он не поднят.

### Stage 1 — ядро (компилируется, 30/30 тестов, shaded jar)
- `hls/`: HlsParser, MasterPlaylist, MediaPlaylist, KeyInfo, Segment.
- `download/`: Decryptor (AES-128), HttpFetcher, SegmentDownloader, ProgressListener,
  RetryPolicy, CancelledException.
- `ffmpeg/FfmpegRunner` (bytedeco, concat/mux/probe/version).
- `util/`: FileNames (sanitize), UrlUtils (canonicalize).
- Тесты: DecryptorTest (5), FileNamesTest (7), HlsParserTest (8), UrlUtilsTest (10).
- IV big-endian right-aligned; sanitize заменяет каждый запрещённый символ отдельно.

### Stage 2 — записано (компилация — последняя проверочная сборка)
- `browser/`: BrowserLauncher (headed, persistent context `<папка>/.profile`, порядок каналов
  chrome → msedge → Chromium), NetworkSniffer (attach(ctx), pump-поток, body только манифесты ≤4 MB),
  SniffEvent.
- `manifest/`: SourceRecord (Kind HLS/DASH/MP4; State FOUND/QUEUED/DOWNLOADING/DONE/ERROR/CANCELLED;
  View-рекорд), SourceRegistry (dedup canonicalize, minSize для MP4, listeners),
  ManifestDetector.
- `download/DownloadQueue`: одна очередь (single-thread daemon), auto-enqueue из FOUND,
  DASH → сообщение «DASH будет поддержан на этапе 7», headers из pageUrl+cookies+UA,
  setLauncher/setIdleHook/setClosing/hasActiveTasks/cancel/retry/enqueue.
- `ui/`: SwingLogAppender (static sink), LogPanel (моноширинная консоль, MAX_LINES=5000, trim
  Document API, «Очистить»/«Открыть папка», автопрокрутка), SourceRowPanel (состояния
  Скачать/Отмена/Отмена/Открыть папку/Повторить, правый клик «Копировать ссылку», цвет по
  состоянию), SourceListPanel (url-поле + «Открыть URL» вне EDT, rows Map по canonicalUrl,
  Timer 700 ms), MainWindow (JSplitPane 40/60, заголовок «HLS Grabber — папка: X»,
  подтверждение при активных загрузках).
- `App.java`: `--gui` (9-й параметр), runGui(): JFileChooser (отмена = выход) → settings.save →
  System.setProperty("hlsdir") → reconfigureLogging (LoggerContext.reset + Joran) → LogPanel +
  SwingLogAppender.setSink → FfmpegRunner → Registry/Queue → daemon-поток BrowserLauncher +
  NetworkSniffer.attach → idle-hook (launcher.close + System.exit(0)) → MainWindow.
- `logback.xml`: GUI-appender в root; `run-gui.bat` (`java -jar target\hls-grabber-1.0-SNAPSHOT.jar --gui`).
- Все GUI-изменения только через SwingUtilities.invokeLater; сеть/FFmpeg вне Swing-потока и
  вне Playwright-обработчиков.

### YouTube — добавлен по запросу пользователя (2026-11-06), GUI-приёмка подтверждена
- **Факт:** веб-плеер (клиент WEB, cver 2.20261030) НЕ отдаёт per-format URL — медиа идёт через
  server-side ABR (SABR): `POST /videoplayback`, тело `application/x-protobuf`, ответ
  `application/vnd.yt-ump` (UMP-кадры: varint type + varint size + payload; MEDIA_HEADER=20 несёт
  itag/contentLength, MEDIA=21 несёт [hid byte][fMP4-байты][вложенный UMP]). Перехват
  HLS/DASH-манифестов на YouTube принципиально слеп; SABR-поток браузера обрывается ~59 с
  (attestation/PO-token) — не реализуется.
- **Рабочий путь:** inner-tube `POST /youtubei/v1/player` клиентом **VISIONOS**
  (clientName=VISIONOS, clientVersion=1.02, INNERTUBE_CONTEXT_CLIENT_NAME=101, UA = Safari macOS
  visionOS, deviceMake=Apple, deviceModel=RealityDevice17,1, osName=visionOS) + **visitorData
  страницы** (window.ytcfg). Запрос — **in-page fetch из страницы** (прямой HTTP из Java:
  400/LOGIN_REQUIRED). Клиенты web/mweb/tv/tv_downgraded/tv_samsung/web_safari/android/ios без
  visitorData — bot-check/400/UNPLAYABLE. Ответ даёт adaptiveFormats с plain `url` (без `n`).
- `youtube/`: `YouTubeApi` (клиент/ключ/payload + `PAGE_FETCH_SCRIPT`), `YouTubePlayer`
  (парсер player-response: `pickVideo(cap)` — max height ≤ cap, cap ниже всех → общий максимум,
  tie → mp4-приоритет затем bitrate; `pickAudio` — m4a-приоритет; `qualities()` — список высот
  для FOUND-строки; `reasonOf` — русские причины LOGIN_REQUIRED/UNPLAYABLE), `YouTubeFormat`,
  `YouTubeDownloader` (Range GET чанками 8 МБ, параллельность = parallelSegments,
  RandomAccessFile по абсолютным смещениям, проверка размера, mux `-c copy` aacBsf=false → mp4,
  temp `outDir/.tmp/<id>`, cleanup).
- Интеграция: `NetworkSniffer.pumpLoop` — детект `UrlUtils.youtubeVideoId(page.url())`
  (watch/shorts/youtu.be) → `captureYouTube` in-page fetch с повтором каждые 3 с до успеха →
  запись `yt:<videoId>` Kind.YOUTUBE (payload=player JSON, qualities, fetch headers);
  `DownloadQueue` — ветка YOUTUBE + `activeYouTube` (отмена); `SourceRowPanel` — прогресс чанков,
  в URL-строке показывается watch-URL; подписи в лог не пишутся (host+path, `UrlUtils.masked`).
- Харнессы: **Repro20** сквозной — PASS (качества [1080,720,480,360,240,144], itag 299 1080p mp4
  395 052 331 B + itag 140 9 093 697 B за 39 с, ffprobe h264 1920×1080 + aac, 561.73 с);
  **Repro21** весь путь приложения (BrowserLauncher профиль → sniffer → YOUTUBE-запись →
  enqueue → DONE, файл 404 494 408 B) — PASS; **Repro22** офлайн-парсер реального player-ответа
  из собранного jar + cap-семантика 1080/720/240/144 — PASS; `YouTubePlayerTest` 7/7.
- Ограничения: нужен браузерный контекст с cookies (без visitorData — bot-check); live и DRM —
  вне рамок; прямые эфиры и DRM-каталог VISIONOS-клиент не покрывает.

### Склейка сегментов (2026-11-07) — дефект «рваного» воспроизведения, исправлен

**Симптом (пользователь, сайт kinogo.luxury / cfnd.cinemap.cc):** видео идёт-идёт → короткий
затык → продолжается. YouTube в порядке (там единый progressive MP4 без склейки).

**Диагноз (цифрами, не на глаз):** в собранном mp4 контента ровно столько, сколько в плейлисте
(145 147 кадров = 6053,9 с при сумме EXTINF 6053,84 с), но **таймлайн длиннее на 159,4 с**:
видео — 1060 дыр по ~0,13 с, аудио — 981 дыра по ~0,18 с (каждая — на границе сегмента).
Плеер на пустых местах таймлайна «замирает». Исходные TS-сегменты стыкуются идеально
(seg1: 0,184→6,107; seg2: 6,149→12,113 — между ними ровно один кадр), плейлист чистый
(без EXT-X-DISCONTINUITY/BYTERANGE/MAP/KEY) — дыры создаёт **наша** склейка.

**Причина:** `concat`-демуксер ffmpeg перебазирует каждый входной файл по **собственной оценке
длительности** контейнера, а для MPEG-TS эта оценка длиннее реальной полезной нагрузки
(к длине добавляется длительность последнего кадра). Каждый файл сдвигается на ~0,1 с больше
нужного → дыра на каждой границе.

**Фикс:** склейка TS-сегментов идёт через **HLS-демуксер** по локальному media-плейлисту
(`FfmpegRunner.concatHls`, `-f hls -i local.m3u8 -c copy -bsf:a aac_adtstoasc`): он сохраняет
относительные таймстампы источника (глобальный сдвиг) — ровно то, что делает HLS-плеер.
Локальный плейлист пишет `SegmentDownloader.localPlaylistText` (исходные EXTINF + имена файлов,
`#EXT-X-MEDIA-SEQUENCE`, `#EXT-X-TARGETDURATION`, `#EXT-X-ENDLIST`). Старый `concat`-путь удалён.

**Проверка:**
- 4 реальных сегмента фильма: было 24,486 с / 10 дыр → стало 24,107 с / 0 дыр.
- 12 реальных сегментов через весь продуктовый пайплайн: видео 1727 кадров span 72,072 с **0 дыр**,
  аудио 3375 кадров span 71,979 с **0 дыр**, длительность 72,113 с.
- Полный фильм (1009 сегментов, 2,14 ГБ): **длительность 6053,92 с, 0 дыр видео, 0 дыр аудио**
  (было 6213,27 с / 141,4 с / 178,3 с). Плейлист-сумма EXTINF = 6053,84 с — совпадает.

**Страховка (другие сайты не должны пострадать):** HLS-демуксер открывает каждый сегмент как
отдельный вход, поэтому сайт, чьи сегменты несут PAT/PMT только в первом, потерял бы их.
Поэтому после сборки длительность результата сверяется с суммой EXTINF
(`SegmentDownloader.looksComplete`); если результат короче 98 % — сборка повторяется
**байтовой склейкой** (`FfmpegRunner.remuxTs`: TS склеивается байтами, как это делает
`cat`, и переносится в mp4) — этот способ работает для любого MPEG-TS и тоже сохраняет
таймстампы источника. Если и байтовая сборка короче ожидаемого, загрузка не падает: в лог уходит
предупреждение (неточные EXTINF — не повод ломать ранее работавшую загрузку).

**Регрессия по сайтам (2026-11-07):**
| Сайт/путь | Было | Стало |
|---|---|---|
| kinogo.luxury / cfnd.cinemap.cc (TS, 1009 сегм.) | 6213,27 с, 141,4 с дыр | 6053,92 с, **0 дыр** |
| 123av / pureheartsoul.site (TS, 2206 сегм., живая загрузка) | ранее принятый файл: 2429 дыр / 279,3 с | 6618,04 с, видео **0 дыр**, аудио 1 дыра 0,08 с в самом конце |
| phncdn (CMAF/fMP4) | дыры между фрагментами | путь не менялся (byte-concat + remux) — см. «Осталось» |
| E2E-харнесс проекта (AES-128, 15 сегм.) | PASS | PASS (60,032 с, 13 778 875 B) |
| видео-only поток (без аудио) | — | PASS: 300 кадров, 0 дыр, ровно 12,000 с (bsf:a на потоке без аудио безопасен) |
| сборка через упакованный exe | — | PASS: exit 0, 1727 кадров, 0 дыр |

123av проверен живым прогоном всего продуктового пути (браузер → sniffing → загрузка по
записанным браузером URL/заголовкам → сборка): CDN 123av отдаёт 403 на «собранные» URL и
проверяет Referer плеер-iframe (`sadie-shop.site`) и cookie CDN-домена — их снимает
`SniffEvent.reqHeaders` → `SourceRecord.setFetchHeaders` → `DownloadQueue`.

**Осталось (не исправлено):** путь CMAF/fMP4 (phncdn) — дыры **между фрагментами** приходят из
исходных `tfdt` фрагментов (проверено на принятом файле: 71 дыра × ~0,09 с на 620 с). Наш
byte-concat сохраняет их, а HLS-плеер перебазирует фрагменты по плейлисту. Проверено: HLS-демуксер
эти дыры **не** закрывает (сохраняет относительные таймстампы источника) — нужен явный
ремап таймстампов (`setts`-bsf / пересборка `stts`), и для приёмки нужна живая подписанная
CMAF-ссылка (текущая истекла).

### Компилация — ✅ BUILD SUCCESS (проверено)
Компиляция: 22 UI-ошибки чинились по итерациям. Фиксы применены и подтверждены сборкой:
- LogPanel переписан под контракт App: конструктор `LogPanel(Supplier<Path>)`, метод `appendLine`,
  trim через `getLineStartOffset`/`replaceRange` (у JTextArea нет getLines/getLineStart/replace).
- SourceRowPanel: импорты `java.awt.event.MouseAdapter/MouseEvent`, константы `BorderLayout.NORTH/CENTER/SOUTH/EAST`.
- SourceListPanel: константы `BorderLayout.*` (java.awt.* есть, SwingConstants для BoxLayout).
- App.java: `ffmpeg.version()` внутри `try`, catch `IOException | InterruptedException`.
Итог: BUILD SUCCESS, тесты 30/30, shaded jar 232 MB (`target\hls-grabber-1.0-SNAPSHOT.jar`).

## Не готово / в работе

1. **GUI-приёмка** — ✅ пройдена пользователем (2026-11-06): HLS/pornhub 1080p CMAF, прямой MP4,
   YouTube (watch-страница → качества → «Скачать» → mp4 со звуком). Остаток — формальный протокол
   приёмки раздела 9 (`docs/tests.md`) на тестовых сайтах.
2. **Сквозной E2E-харнесс** — ✅ PASS (прогон 3, 2026-11-05): сервер 8123, AES-128 HLS 15 сегментов,
   headed Chrome, sniffing → auto-enqueue → decrypt+concat → ffprobe; mp4 13 778 939 байт.
   История прогонов: прогон 1 FAIL (`Loader.cacheResource("ffmpeg.exe")` → null из fat jar) —
   исправлено на `Loader.load(ffmpeg.class)`/`Loader.load(ffprobe.class)`; прогон 2 FAIL
   (headful launchPersistentContext падал на всех каналах, TargetClosedError exitCode=2147483651) —
   бисект флагов (`tmp\Repro.java`): невалидное значение `--autoplay-policy=user-gestures-required-enabled`
   убивает Chrome; заменено на валидное `--autoplay-policy=user-gesture-required`; прогон 3 PASS.
3. **Настройки без UI** (TZ): settings.properties — targetHeight/minSizeBytes/parallelSegments/
   downloadDir/userAgent; UI настроек не в Этапе 2.
4. Остаток: **DASH** (парсер SegmentTemplate/SegmentTimeline/BaseURL + загрузчик) — по анкет
   2026-11-05 последним; **remap таймстампов CMAF/fMP4-фрагментов** (дыры между фрагментами,
   см. «Склейка сегментов»); **приёмка раздела 9** (протокол + тестовые сайты);
   live/DRM-проверки на реальном контенте.
5. **Тестовые сайты/аккаунты для Этапа 8** — пользователь обещал, ожидаются.
6. Решения анкетой — зафиксированы в PLAN, раздел 7 (2026-11-05).

## Как возобновить работу

1. Сборка (формула, build.log вне target/):
   `Set-Location 'D:\Java\Downloader\Downloader_hub'; $ErrorActionPreference = 'Continue'; mvn clean package 2>&1 | Out-File -Encoding utf8 'D:\Java\Downloader\build.log'; Write-Host "EXIT=$LASTEXITCODE"`
   (на фоне; чтение: `Get-Content build.log | Select-String 'ERROR|Tests run:|BUILD'`).
2. E2E-харнесс (сервер 8123, генерация HLS, headed Chrome, полный продуктовый пайплайн, PASS/FAIL+exit 0/1):
   `java -cp 'target\hls-grabber-1.0-SNAPSHOT.jar;target\test-classes' com.example.hlsgrabber.E2eHarness 'D:\Java\Downloader\spikes\r1-r2'`
   (пересборка перед прогоном; лог `D:\Java\Downloader\e2e.log`).
3. CLI-приёмка: `java -jar target\hls-grabber-1.0-SNAPSHOT.jar --url <m3u8> --out <folder> [--name X --referer R --ua U --cookie C --parallel N --ffmpeg-selftest]`.
4. GUI: `run-gui.bat` (из Downloader_hub) или `java -jar target\hls-grabber-1.0-SNAPSHOT.jar --gui`.
5. YouTube-харнессы (single-file source mode против fat jar):
   `java -cp 'target\hls-grabber-1.0-SNAPSHOT.jar' D:\Java\Downloader\tmp\Repro22.java` — офлайн-парсер
   реального player-ответа и cap-семантика; `Repro20.java` / `Repro21.java` — сквозной и полный путь
   приложения. Дампы player-ответов: `D:\Java\Downloader\tmp\yt-probe\` (`r-visionos.json` — 78 форматов
   VISIONOS, `r-web_safari.json`, `player-*.json` — закрытые клиенты, `sabr-resp-*.bin` — UMP-ответы).
   Скачанный тестовый mp4: `D:\Java\Downloader\tmp\yt-int\World's Largest Ramp!.mp4` (404 494 408 B).

## Известные грабли (пережито)

- Playwright: `Playwright.create()` → `playwright.chromium()`; `launchPersistentContext` — нестатический;
  `setChannel((String) ch)`, снятие канала — `setChannel((String) null)` (перегрузка BrowserChannel).
- Response.body() только на pump-поток; headerValue lowercase; Cookie — public поля name/value.
- HttpFetcher.getToFile: `BodyHandlers.ofByteArrayConsumer` принимает `Consumer<Optional<byte[]>>`
  (пустой Optional = конец тела, пропускать; `chunk.get()`); отмена CancellationException →
  CompletionException → CancelledException; `.part` удаляется; Files.move REPLACE_EXISTING.
- JTextArea: нет `getLines()/getLineStartOf(int)/replace(int,int,String)` — только Document API
  (`getLineCount`, `getLineStartOffset`, `replaceRange`).
- `append` возвращает void — не цепочка `append(a).append(b)`.
- goлые CENTER/NORTH в add() не компилируются — `BorderLayout.NORTH` (java.awt) или
  `SwingConstants.CENTER` (BoxLayout alignmentX); `java.awt.*` не покрывает `java.awt.event.*`.
- FfmpegRunner.version()/concat/mux/probe бросают `IOException, InterruptedException`.
- logback: `hlsdir` задавать System.setProperty до reconfigureLogging (reset + Joran).
- PowerShell 5.1: нет &&/||; single-quoted literals; UTF-8; `run_in_background` boolean true.
- build.log строго вне target/; docs UTF-8 LF.
- `PLAN_HLS_Grabber.md` редактировался внешним IDE-буфером — проверять grep после edit.
- javap только по plain jar (sources.jar → «class not found»), JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot.
- Loader из fat jar: `Loader.cacheResource("ffmpeg.exe")` → null (findResource ищет ресурс относительно
  пакета класса-вызывающего). Канонически: `Loader.load(ffmpeg.class)`/`Loader.load(ffprobe.class)` —
  properties классов регистрируют executable, возвращают путь exe (R4 spike). Native-классификатор
  `ffmpeg-…-windows-x86_64.jar` объявлен dependency'ем, shade кладёт exe/dll в fat jar.
- Chrome launch-флаги: невалидное значение флага убивает браузер сразу после запуска.
  `--autoplay-policy=user-gestures-required-enabled` → Chrome падает (exitCode=2147483651=0x80000003,
  Playwright TargetClosedError), в переборе каналов маскируется под «все каналы недоступны».
  Валидные значения autoplay-policy: `user-gesture-required`, `document-user-activation-required`,
  `no-user-gesture-required`. Строки crashpad «Settings version is not 7/1» — не фатальный шум.
  Headful+persistent context (channel chrome) работает, запуск ~400 мс.
- Бисект флагов: java single-file source mode прямо из fat jar — `java -cp target\...jar tmp\Repro.java`
  (репро компилиется на лету против классов jar; `LaunchPersistentContextOptions`, не `LaunchOptions`).
- E2E-харнесс: `HttpServer` (com.sun.net.httpserver) — non-daemon потоки; выход/exception main не завершает
  JVM; висящий java-процесс держит fat jar → `mvn clean` «Failed to delete …jar» (лечить
  `Stop-Process` по PID из `Get-CimInstance Win32_Process` CommandLine).
- YouTube: веб-клиент не отдаёт URL (SABR/UMP), DASH/HLS-манифеста на YouTube нет — не искать;
  in-page fetch обязателен (прямой HTTP → 400/LOGIN_REQUIRED); `page.evaluate` payload — объект/Map,
  не строка; Playwright `Map` с Long → «Unsupported type of argument»; подпись `ip` в URL привязана
  к egress-IP (иначе 403); `probeRangeTotal`: 206 → total, 200 → 0 (без Range); `UrlUtils.canonicalize`
  для watch-URL дропает `v` → ключ записи `yt:<videoId>`, в UI показывать watch-URL.
- **CLI-парсер (2026-11-07):** в `Options.parse` значение флага читалось через `next(args, i)`, но
  сам токен значения не пропускался → цикл попадал на него и уходил в `default -> return null` →
  любой CLI-вызов с `--url/--out` выходил с кодом 2 (usage) и не создавал папку. Лечится `i++`
  в каждом case со значением.
- **Склейка: `concat`-демуксер даёт дыры на границах MPEG-TS** (см. «Склейка сегментов»); HLS-
  демуксер по локальному плейлисту их не даёт. HLS-демуксер **не** перебазирует фрагменты по
  плейлисту — сохраняет относительные таймстампы источника (глобальный сдвиг).
- **PowerShell 5.1, локаль ru-RU:** `[double]::TryParse("0.083000")` → false (запятая — разделитель);
  нужен `NumberStyles.Float` + `CultureInfo.InvariantCulture`. Иначе все расчёты «пустые».
- **Start-Process -ArgumentList:** путь с пробелами (например имя mp4) должен быть в кавычках
  явно: `('"' + $file + '"')` — иначе ffmpeg/ffprobe видят только первый фрагмент пути.
- **stderr от ffmpeg в PowerShell:** при `$ErrorActionPreference='Stop'` любая строка в stderr
  (даже warning) роняет скрипт через NativeCommandError → запускать через Start-Process с
  `-RedirectStandardError` в файл.
- **Плейлисты с защитой от ботов:** часть CDN (cfnd.cinemap.cc) отдаёт тело плейлиста как
  последовательность байтов («35 69 88 84 …» = «#EXTM3U») для «не-браузерных» клиентов; наш
  HttpFetcher получает нормальный m3u8.

## Ключевые файлы

- `Downloader_hub/pom.xml`, `run-gui.bat`
- `src/main/java/com/example/hlsgrabber/App.java` (+9-й параметр --gui, runGui, reconfigureLogging)
- `src/main/resources/logback.xml` (GUI-appender)
- `src/main/java/.../ui/{SwingLogAppender,LogPanel,SourceRowPanel,SourceListPanel,MainWindow}.java`
- `src/main/java/.../browser/{BrowserLauncher,NetworkSniffer,SniffEvent}.java`
- `src/main/java/.../manifest/{SourceRecord,SourceRegistry,ManifestDetector}.java`
- `src/main/java/.../download/{DownloadQueue,HttpFetcher,SegmentDownloader,Decryptor,...}.java`
- `src/docs/{TZ_HLS_Grabber.md,PLAN_HLS_Grabber.md,spikes/R3-R7-report.md}`
- Харнесс: `D:\Java\Downloader\spikes\r1-r2\`
- `D:\Java\Downloader\build.log` — текущий лог сборки; `D:\Java\Downloader\e2e.log` — лог прогона 3 (PASS)
- `tmp\Repro.java` — бисект launch-флагов Chrome (single-file source mode), `tmp\Loader.java` — исходник bytedeco Loader
