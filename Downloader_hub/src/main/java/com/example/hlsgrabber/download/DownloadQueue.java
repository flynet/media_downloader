package com.example.hlsgrabber.download;

import com.example.hlsgrabber.browser.BrowserLauncher;
import com.example.hlsgrabber.config.Settings;
import com.example.hlsgrabber.ffmpeg.FfmpegRunner;
import com.example.hlsgrabber.manifest.SourceRecord;
import com.example.hlsgrabber.manifest.SourceRegistry;
import com.example.hlsgrabber.util.FileNames;
import com.example.hlsgrabber.util.UrlUtils;
import com.example.hlsgrabber.youtube.YouTubeDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sequential download queue (Stage 2; TZ 6.1 parallel segments applies inside one download,
 * whole-source parallelism comes in stage 6). Auto-enqueues detected sources; headers for the
 * fetcher are taken from the page (Referer/UA/Cookie from the browser profile, TZ 6.1).
 */
public final class DownloadQueue {

    private static final Logger log = LoggerFactory.getLogger(DownloadQueue.class);

    public interface IdleHook {
        void onQueueIdle();
    }

    private final SourceRegistry registry;
    private final Settings settings;
    private final FfmpegRunner ffmpeg;
    private final ExecutorService worker;
    private final Map<String, SegmentDownloader> active = new ConcurrentHashMap<>();
    private final Map<String, YouTubeDownloader> activeYouTube = new ConcurrentHashMap<>();
    private final AtomicInteger running = new AtomicInteger(0);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private volatile BrowserLauncher launcher;
    private volatile IdleHook idleHook;

    public DownloadQueue(SourceRegistry registry, Settings settings, FfmpegRunner ffmpeg) {
        this.registry = registry;
        this.settings = settings;
        this.ffmpeg = ffmpeg;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "download-worker");
            t.setDaemon(true);
            return t;
        });
        registry.addListener(this::onRecord);
    }

    public void setLauncher(BrowserLauncher l) {
        launcher = l;
    }

    public void setIdleHook(IdleHook h) {
        idleHook = h;
    }

    public void setClosing(boolean v) {
        closing.set(v);
        if (v && running.get() == 0) {
            IdleHook h = idleHook;
            if (h != null) {
                h.onQueueIdle();
            }
        }
    }

    public boolean hasActiveTasks() {
        return running.get() > 0;
    }

    /**
     * TZ 9 (line 192): downloads start ONLY after the «Скачать» button click — rows stay
     * in FOUND until the user presses the button (SourceRowPanel → RowActions.enqueue).
     * This hook only annotates records; it never enqueues.
     */
    private void onRecord(SourceRecord rec) {
        if (rec.state() == SourceRecord.State.FOUND && !closing.get()) {
            if (rec.kind == SourceRecord.Kind.DASH) {
                rec.setMessage("DASH будет поддержан на этапе 7");
                registry.notifyChanged(rec);
            }
        }
    }

    /** Button-driven enqueue: the ONLY path that starts a download (TZ 9 line 192). */
    public void enqueue(SourceRecord rec) {
        enqueue(rec, 0);
    }

    /** Enqueue with the selected video height; 0 means the best available quality. */
    public void enqueue(SourceRecord rec, int qualityHeight) {
        if (closing.get()) {
            return;
        }
        rec.setState(SourceRecord.State.QUEUED);
        registry.notifyChanged(rec);
        worker.submit(() -> run(rec, qualityHeight));
    }

    private void run(SourceRecord rec, int qualityHeight) {
        running.incrementAndGet();
        try {
            rec.setState(SourceRecord.State.DOWNLOADING);
            rec.setStartedAtMs(System.currentTimeMillis());
            rec.setStage("старт");
            registry.notifyChanged(rec);

            String pageUrl = rec.pageUrl == null ? "" : rec.pageUrl;
            log.info("Начинаю загрузку: {} — со страницы {}", rec.canonicalUrl,
                    pageUrl.isEmpty() ? "?" : pageUrl);
            BrowserLauncher l = launcher;
            // headers: prefer the recorded request headers of the sniffer event (exact
            // cookie/referer/origin the browser sent on the successful CDN request)
            String cookie = rec.cookieHeader() != null ? rec.cookieHeader()
                    : (l != null ? l.cookieHeader(pageUrl) : "");
            String ua = settings.userAgent != null ? settings.userAgent
                    : (rec.userAgent() != null ? rec.userAgent()
                       : (l != null ? l.userAgent() : null));
            String referer = rec.referer() != null && !rec.referer().isEmpty() ? rec.referer()
                    : (pageUrl.isEmpty() ? null : pageUrl);
            String origin = rec.origin() != null && !rec.origin().isEmpty() ? rec.origin()
                    : (referer == null ? null : UrlUtils.originOf(referer));
            HttpFetcher.Headers h = new HttpFetcher.Headers(
                    referer,
                    origin,
                    ua,
                    cookie.isEmpty() ? null : cookie);
            HttpFetcher fetcher = new HttpFetcher(h);
            Path outDir = Path.of(settings.downloadDir);
            Path out;
            ProgressListener listener = progressListener(rec);
            // signed original URL (CDN signatures must survive canonicalization)
            String fetchUrl = rec.masterUrl() != null ? rec.masterUrl() : rec.canonicalUrl;
            String name = rec.pageTitle != null && !rec.pageTitle.isBlank()
                    ? rec.pageTitle : nameFromUrl(rec.canonicalUrl);

            if (rec.kind == SourceRecord.Kind.MP4) {
                out = fetcher.getToFile(fetchUrl,
                        FileNames.build(outDir.toFile(), name, ".mp4").toPath(),
                        settings.minSizeBytes, listener);
            } else if (rec.kind == SourceRecord.Kind.YOUTUBE) {
                // YouTube: the web player offers no downloadable URL (server-side ABR),
                // so the record carries the inner-tube player response and the worker
                // downloads the signed streams itself (chunked GET + FFmpeg mux).
                YouTubeDownloader yd = new YouTubeDownloader(fetcher, ffmpeg, settings.parallelSegments);
                yd.setListener(listener);
                activeYouTube.put(rec.canonicalUrl, yd);
                try {
                    out = yd.download(rec.payload(), outDir, name, qualityHeight);
                } finally {
                    activeYouTube.remove(rec.canonicalUrl);
                }
            } else {
                SegmentDownloader sd = new SegmentDownloader(fetcher, ffmpeg, settings.parallelSegments);
                sd.setListener(listener);
                sd.setRecord(rec);
                active.put(rec.canonicalUrl, sd);
                out = sd.runMaster(fetchUrl, outDir, name, qualityHeight);
                active.remove(rec.canonicalUrl);
            }
            rec.setOutPath(out.toString());
            rec.setState(SourceRecord.State.DONE);
            rec.setStage("готово");
            log.info("Готово: {} → {}", rec.canonicalUrl, out);
        } catch (SegmentDownloader.SkipException e) {
            rec.setState(SourceRecord.State.ERROR);
            rec.setMessage(e.getMessage());
            log.warn("Источник пропущен: {} — {}", rec.canonicalUrl, e.getMessage());
        } catch (CancelledException e) {
            rec.setState(SourceRecord.State.CANCELLED);
            rec.setStage("отменено");
            log.info("Отменено: {}", rec.canonicalUrl);
        } catch (Exception e) {
            rec.setState(SourceRecord.State.ERROR);
            rec.setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("Ошибка: {} — {}", rec.canonicalUrl, e.toString(), e);
        } finally {
            active.remove(rec.canonicalUrl);
            running.decrementAndGet();
            registry.notifyChanged(rec);
            if (closing.get() && running.get() == 0) {
                IdleHook h = idleHook;
                if (h != null) {
                    h.onQueueIdle();
                }
            }
        }
    }

    /** Fallback name when the page title is unavailable: last path segment without extension. */
    static String nameFromUrl(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        int slash = path.lastIndexOf('/');
        String seg = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = seg.lastIndexOf('.');
        if (dot > 0) {
            seg = seg.substring(0, dot);
        }
        return seg.isEmpty() ? "video" : seg;
    }

    public void cancel(SourceRecord rec) {
        rec.cancel();
        SegmentDownloader sd = active.get(rec.canonicalUrl);
        if (sd != null) {
            sd.cancel();
        }
        YouTubeDownloader yd = activeYouTube.get(rec.canonicalUrl);
        if (yd != null) {
            yd.cancel();
        }
        log.info("Отмена запроса: {}", rec.canonicalUrl);
    }

    public void retry(SourceRecord rec) {
        rec.setState(SourceRecord.State.FOUND);
        rec.setMessage(null);
        registry.notifyChanged(rec);
    }

    private ProgressListener progressListener(SourceRecord rec) {
        return new ProgressListener() {
            private long lastNotify;

            @Override
            public void onProgress(int done, int total, long bytes, String stage) {
                rec.setSegments(done, total);
                rec.setBytesReceived(bytes);
                rec.setStage(stage);
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 400) {
                    lastNotify = now;
                    registry.notifyChanged(rec);
                }
            }

            @Override
            public boolean isCancelled() {
                return rec.isCancelled();
            }
        };
    }
}
