package com.flynet.hlsgrabber.youtube;

import com.flynet.hlsgrabber.download.CancelledException;
import com.flynet.hlsgrabber.download.HttpFetcher;
import com.flynet.hlsgrabber.download.ProgressListener;
import com.flynet.hlsgrabber.download.SegmentDownloader;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.util.UrlUtils;
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
 * YouTube download pipeline (stage 7).
 *
 * <p>Real-site facts (Nov 2026, verified by harness):
 * <ul>
 *   <li>the web player serves media over server-side ABR (SABR/UMP) — no per-format
 *       URLs, so the generic network sniffer cannot see anything to download;</li>
 *   <li>the VISIONOS inner-tube client still returns CLASSIC signed URLs, and those
 *       URLs answer a plain Range GET with 206 + the expected content range;</li>
 *   <li>adaptive formats are video-only + audio-only, so the tracks are downloaded
 *       separately and muxed with FFmpeg (-c copy, no re-encode).</li>
 * </ul>
 *
 * <p>Chunking follows the segment-parallelism setting (TZ 6.1): the file is split into
 * 8 MB ranges fetched concurrently and written at their absolute offsets.
 */
public final class YouTubeDownloader {

    private static final Logger log = LoggerFactory.getLogger(YouTubeDownloader.class);

    /** Chunk size of the parallel range download. */
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
     * Downloads the video (and its audio track) and muxes them into a single file.
     *
     * @param playerJson raw inner-tube player response
     * @param outDir     target directory
     * @param name       output file base name
     * @param cap        selected max height (0 = best available)
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
        log.info("YouTube: selected {}p (itag {}, {}){}",
                video.height(), video.itag(), video.mimeType(),
                audio == null ? ", no audio" : " + audio itag " + audio.itag());

        Path task = Files.createDirectories(outDir.resolve(".tmp").resolve(
                Long.toString(System.nanoTime(), 36)));
        try {
            Path videoFile = task.resolve("video.mp4");
            long videoBytes = downloadTrack(video, videoFile, "video");
            Path out = com.flynet.hlsgrabber.util.FileNames.build(outDir.toFile(), name, ".mp4").toPath();
            if (audio == null) {
                ffmpeg.remux(videoFile, out, line -> {
                });
            } else {
                Path audioFile = task.resolve("audio.m4a");
                long audioBytes = downloadTrack(audio, audioFile, "audio");
                listener.onProgress(0, 0, 0, "muxing");
                ffmpeg.mux(videoFile, audioFile, out, line -> {
                }, false);
                log.info("YouTube: downloaded video {} B, audio {} B", videoBytes, audioBytes);
            }
            return out;
        } finally {
            deleteRecursively(task);
        }
    }

    /**
     * Range-chunk download of one track into {@code target}: the file is written by
     * absolute offsets, so chunks may arrive in any order.
     */
    private long downloadTrack(YouTubeFormat format, Path target, String stage) throws Exception {
        long total = format.contentLength();
        if (total <= 0) {
            total = fetcher.probeRangeTotal(format.url());
        }
        if (total <= 0) {
            // no Range support / unknown size: single streaming GET
            log.info("YouTube: {} without Range, full download", stage);
            fetcher.getToFile(format.url(), target, 0, listener);
            return Files.size(target);
        }
        int chunks = (int) Math.max(1, (total + CHUNK - 1) / CHUNK);
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        log.info("YouTube: {} itag {} — {} bytes, {} chunks of {} (parallel {})",
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
                    listener.onProgress(done.incrementAndGet(), chunks, b, "download " + stage);
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
            throw new IOException("YouTube: " + stage + " size mismatch (" + size + " != " + total + ")");
        }
        listener.onProgress(chunks, chunks, size, stage + " done");
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

    /** Masked URL for logs (signed params never reach the console). */
    static String masked(String url) {
        return UrlUtils.masked(url);
    }
}
