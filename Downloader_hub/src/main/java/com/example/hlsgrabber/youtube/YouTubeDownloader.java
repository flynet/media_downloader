package com.example.hlsgrabber.youtube;

import com.example.hlsgrabber.download.CancelledException;
import com.example.hlsgrabber.download.HttpFetcher;
import com.example.hlsgrabber.download.ProgressListener;
import com.example.hlsgrabber.download.SegmentDownloader;
import com.example.hlsgrabber.ffmpeg.FfmpegRunner;
import com.example.hlsgrabber.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Конвейер загрузки YouTube (стадия 7).
 * YouTube download pipeline (stage 7).
 *
 * <p>Факты реального сайта (ноябрь 2026, проверено харнессом):
 * Real-site facts (Nov 2026, verified by harness):
 * <ul>
 *   <li>веб-плеер отдаёт медиа серверным ABR (SABR/UMP) — URL на формат отсутствуют,
 *       поэтому общий сетевой sniffer не видит ничего, что можно скачать;
 *       the web player serves media over server-side ABR (SABR/UMP) — no per-format
 *       URLs, so the generic network sniffer cannot see anything to download;</li>
 *   <li>inner-tube клиент VISIONOS по-прежнему возвращает CLASSIC подписанные URL,
 *       и эти URL отвечают обычному Range GET кодом 206 + ожидаемый диапазон;
 *       the VISIONOS inner-tube client still returns CLASSIC signed URLs, and those
 *       URLs answer a plain Range GET with 206 + the expected content range;</li>
 *   <li>адаптивные форматы — видео-_only + аудио-only, поэтому дорожки скачиваются
 *       отдельно и микшируются FFmpeg (-c copy, без перекодирования).
 *       adaptive formats are video-only + audio-only, so the tracks are downloaded
 *       separately and muxed with FFmpeg (-c copy, no re-encode).</li>
 * </ul>
 *
 * <p>Чанкование следует настройке параллелизма сегментов (TZ 6.1): файл делится на
 * 8 МБ диапазоны, загружаемые одновременно и записываемые по абсолютным смещениям.
 * Chunking follows the segment-parallelism setting (TZ 6.1): the file is split into
 * 8 MB ranges fetched concurrently and written at their absolute offsets.
 */
public final class YouTubeDownloader {

    private static final Logger log = LoggerFactory.getLogger(YouTubeDownloader.class);

    /** Размер чанка параллельной загрузки диапазона.
     *  Chunk size of the parallel range download. */
    private static final long CHUNK = 8L * 1024 * 1024;

    private final HttpFetcher fetcher;
    private final FfmpegRunner ffmpeg;
    private final int parallel;
    private ProgressListener listener = ProgressListener.NOOP;
    private volatile boolean cancelled;

    public YouTubeDownloader(HttpFetcher fetcher, FfmpegRunner ffmpeg, int parallel) {
        this.fetcher = fetcher;
        this.ffmpeg = ffmpeg;
        this.parallel = Math.max(1, parallel);
    }

    public void setListener(ProgressListener l) {
        this.listener = l == null ? ProgressListener.NOOP : l;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Скачивает видео (и его аудиодорожку) и микширует их в один файл.
     * Downloads the video (and its audio track) and muxes them into a single file.
     *
     * @param playerJson сырой inner-tube player response
     *                   / raw inner-tube player response
     * @param outDir     целевая директория / target directory
     * @param name       базовое имя выходного файла / output file base name
     * @param cap        выбранная макс. высота (0 = лучшее доступное)
     *                   / selected max height (0 = best available)
     */
    public Path download(String playerJson, Path outDir, String name, int cap) throws Exception {
        YouTubePlayer.Player player = YouTubePlayer.parse(playerJson);
        if (!player.ok()) {
            throw new SegmentDownloader.SkipException(
                    "YouTube: " + YouTubePlayer.reasonOf(player));
        }
        if (player.live()) {
            throw new SegmentDownloader.SkipException("live video stream");
        }
        YouTubeFormat video = YouTubePlayer.pickVideo(player, cap);
        if (video == null) {
            throw new SegmentDownloader.SkipException("no usable video format");
        }
        YouTubeFormat audio = YouTubePlayer.pickAudio(player);
        log.info("YouTube: выбрано {}p (itag {}, {}){}",
                video.height(), video.itag(), video.mimeType(),
                audio == null ? ", без аудио" : " + аудио itag " + audio.itag());

        Path task = Files.createDirectories(outDir.resolve(".tmp").resolve(
                Long.toString(System.nanoTime(), 36)));
        try {
            Path videoFile = task.resolve("video.mp4");
            long videoBytes = downloadTrack(video, videoFile, "видео");
            Path out = com.example.hlsgrabber.util.FileNames.build(outDir.toFile(), name, ".mp4").toPath();
            if (audio == null) {
                ffmpeg.remux(videoFile, out, line -> {
                });
            } else {
                Path audioFile = task.resolve("audio.m4a");
                long audioBytes = downloadTrack(audio, audioFile, "аудио");
                listener.onProgress(0, 0, 0, "мультиплексирование");
                ffmpeg.mux(videoFile, audioFile, out, line -> {
                }, false);
                log.info("YouTube: скачано видео {} B, аудио {} B", videoBytes, audioBytes);
            }
            return out;
        } finally {
            deleteRecursively(task);
        }
    }

    /**
     * Загрузка одной дорожки чанками по диапазонам в {@code target}: файл пишется
     * по абсолютным смещениям, поэтому чанки могут прийти в любом порядке.
     * Range-chunk download of one track into {@code target}: the file is written by
     * absolute offsets, so chunks may arrive in any order.
     */
    private long downloadTrack(YouTubeFormat format, Path target, String stage) throws Exception {
        long total = format.contentLength();
        if (total <= 0) {
            total = fetcher.probeRangeTotal(format.url());
        }
        if (total <= 0) {
            // нет поддержки Range / неизвестный размер: одиночный streaming GET
            // no Range support / unknown size: single streaming GET
            log.info("YouTube: {} без Range, скачивание целиком", stage);
            fetcher.getToFile(format.url(), target, 0, listener);
            return Files.size(target);
        }
        int chunks = (int) Math.max(1, (total + CHUNK - 1) / CHUNK);
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        log.info("YouTube: {} itag {} — {} байт, {} чанков по {} (параллельно {})",
                stage, format.itag(), total, chunks, CHUNK, parallel);

        ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "yt-chunk");
            t.setDaemon(true);
            return t;
        });
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(total);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < chunks; i++) {
                final long start = (long) i * CHUNK;
                final long end = Math.min(total - 1, start + CHUNK - 1);
                futures.add(pool.submit(() -> {
                    if (cancelled || listener.isCancelled()) {
                        throw new CancelledException(format.url());
                    }
                    byte[] part = fetcher.rangeGet(format.url(), start, end);
                    synchronized (raf) {
                        raf.seek(start);
                        raf.write(part);
                    }
                    long b = bytes.addAndGet(part.length);
                    listener.onProgress(done.incrementAndGet(), chunks, b, "загрузка " + stage);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        long size = Files.size(target);
        if (size != total) {
            throw new IOException("YouTube: размер " + stage + " не совпал (" + size + " != " + total + ")");
        }
        listener.onProgress(chunks, chunks, size, stage + " готово");
        return size;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("cleanup failed: {}", e.toString());
                }
            });
        } catch (IOException e) {
            log.debug("cleanup failed: {}", e.toString());
        }
    }

    /** Замаскированный URL для логов (подписанные параметры никогда не попадают в консоль).
     *  Masked URL for logs (signed params never reach the console). */
    static String masked(String url) {
        return UrlUtils.masked(url);
    }
}
