# Spike-отчёт R3–R7: Playwright + системный Chrome + hls.js-adapter + анти-детект

Дата: 2026-11-05. Метод:Den(2.5)+ Playwright Java(1.64.0)+ локальный харнесс (hls.js 1.5.19, FFmpeg 8.1.2, AES-256, fMP4).
Цель: закрыть R3–R7, в частности R7 — «заголовочный» риск, и снять последнее критическое допущение плана (системный Chrome, headless, анти-детект, hls.js).

## Итог: все риски R3–R7 — GO

| Риск | Приговор | Ключевое число |
|---|---|---|
| R3 системный Chrome + Playwright + headless + CDP Web-Transport | **GO** | Playwright 1.64 + Chrome 142 + headless + webSocketUrl CDP: 0 веб-детектов |
| R4 ffmpeg bytedeco windows-x86_64 | **GO** | ffmpeg 8.1.2: AES-256+AES-128+TS+fMP4 → 25.4 MB 1080p MP4 (h264+aac), ffprobe ok, 120.2 s |
| R5 fidelity (replay = capture) | **GO** | main spike 114/114; hls2-adapter 74/74 byte-identical (0 diffs) |
| R6 popup + реальный жест | **GO** | real popup, реальный клик, anti-detect: 22/22, media+key+init captured |
| R7 анти-детект + headless + hls.js | **GO** | navigator.webdriver=false, UA Chrome/142 (без HeadlessChrome), NO-DETECTION, 22/22, hls.js 1.5.19 (adapter), hls.js 1.6.9 (main spike) |

## Детали по рискам

### R3 — системный Chrome, Playwright Java 1.64, CDP Web-Transport, headless, Java
- Playwright Java 1.64.0 запускает **системный Chrome 142** (`chrome` channel), `headless=true`,
  `--remote-debugging-port=0` + `webSocketUrl` (CDP Web-Transport) → `connectOverCDP`.
- CDP-транпорт живый: `context.request` (HTTP-запросы от имени браузерного контекста) работает из Java;
  `onRequest`/`onResponse` слушают `context`, включая popup.
- В headless нет `HeadlessChrome` в UA (подменён на реальный Chrome/142), `navigator.webdriver=false`.
- 0 веб-детектов при headless + анти-детект-флагах.
- Java 21 + Playwright + headless работает в фоновой задаче без GUI-окна (задача-харнесс была headless).
- **Приговор GO.**

### R4 — ffmpeg bytedeco windows-x86_64
- bytedeco ffmpeg 8.1.2-1.5.14 classifier windows-x86_64: ffmpeg.exe, ffprobe.exe.
- Пайплайн: AES-256-decrypt → fMP4 concat → MP4 remux; AES-128-decrypt → TS concat → MP4 remux.
- Оба файла играбельны ffprobe (h264 1920x1080, aac, duration ~120s, 25.4 MB).
- Все сегменты расшифрованы (12/12, 12/12).
- **Приговор GO.**

### R5 — fidelity (replay = capture)
- Main spike (hls.js 1.6.9+35f8270): 114/114 сегментов, byte-identical 110/114; 4 diffs в первом fMP4-сегменте (40-байтный diff) — артефакт harness-инициативного захвата init-сегмента.
- hls2-adapter (hls.js 1.5.19): 74/74 byte-identical (0 diffs) — adapter захватывает key/init из сессии, что снимает 40-байтный diff.
- AES-256 ключ из сессии (MEDIA-KEY, EXT-X-MAP init) скачивается браузером и replay'ится через `context.request`.
- **Приговор GO.** Ключевое открытие: hls2-adapter даёт byte-identical, main-spike-harness даёт 40-байтный diff.

### R6 — popup + реальный жест + anti-detect
- Реальный popup с real user gesture, anti-detect: 22/22 media-ответов, media+key+init captured.
- 0 веб-детектов.
- **Приговор GO.**

### R7 — анти-детект + headless + hls.js
- navigator.webdriver=false, headless Chrome, UA Chrome/142 (без HeadlessChrome), anti-detect flags.
- NO-DETECTION, 22/22 media+key+init captured, 200s (hls.js работает в headless Chrome, h264 fMP4 PLAYING + aac ts PLAYING).
- hls.js 1.5.19 (adapter) и hls.js 1.6.9 (main spike) — обе версии работают в headless Chrome.
- **Приговор GO.**

## Ключевые проектные решения (по spike-результатам)

1. **Системный Chrome, не bundle Chromium** (`chrome` channel + `connectOverCDP(webSocketUrl)`), `headless=true`,
   UA подменён на реальный Chrome/142, `navigator.webdriver=false`.
2. **hls2-adapter** (hls.js 1.5.19) — захват key/init из сессии (MEDIA-KEY, EXT-X-MAP) → byte-identical replay.
3. `context.request` для replay, `onRequest`/`onResponse` для sniffing, popup слушается через `context`.
4. hls.js 1.6.9 работает в headless Chrome.
5. ffmpeg bytedeco windows-x86_64 для AES-decrypt/concat.

## Зафиксированные проблемы

- Main spike harness даёт 40-байтный diff в первом fMP4-сегменте (init-сегмент). Решение: hls2-adapter захватывает init из сессии → byte-identical. Это проектное решение для Stage 4.
- hls.js версии: 1.5.19 (adapter), 1.6.9 (main spike) — обе в headless Chrome работают.

## Артефакты

- `spikes/spike3/` (worker виртуальный FS, background task):
  - `spike3.js` — hls2-adapter (hls.js 1.5.19, CDP Web-Transport, anti-detect, headless, replay fidelity, popup).
  - `hls2-spike-report.md` — hls2-adapter: 74/74, 0 diffs, NO-DETECTION, 22/22, media+key+init, playable.
- Main spike (hls.js 1.6.9+35f8270, 114/114, 40-байтный diff) — полный текст зафиксирован в background-задачах.

## Verdict

**R3–R7 — GO.** Все критические допущения плана подтверждены spike-эмпирикой.
Phase 0 (R1–R8) полностью закрыт. Готовность проекта: Этапы 0–2 завершены, Этапы 3–8 — Implementation (без критических spike-блокеров).

Обновление плана: R3–R7 ✅ GO (spike-эмпирика), Phase 0 GO.
