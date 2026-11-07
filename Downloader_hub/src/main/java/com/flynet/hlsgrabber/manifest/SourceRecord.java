package com.flynet.hlsgrabber.manifest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Обнаруженный источник (HLS-манифест, DASH-манифест, прямой MP4) и его состояние.
 * Изменяемые поля, безопасные для многопоточности; UI читает через snapshot()
 * (неизменяемое представление).
 *
 * A detected source (HLS manifest, DASH manifest, direct MP4) and its state.
 * Thread-safe mutable fields; UI reads via snapshot() (immutable view).
 */
public final class SourceRecord {

    /** Доступные высоты видео (p, px) по убыванию, как объявлено в манифесте.
     *  Available video heights (p, px), descending, as reported by the manifest. */
    private volatile List<Integer> qualities = List.of();

    public enum Kind { HLS, DASH, MP4, YOUTUBE }
    public enum State { FOUND, QUEUED, DOWNLOADING, DONE, ERROR, CANCELLED }

    public final String canonicalUrl;
    public final Kind kind;
    public final String pageUrl;
    public final String pageTitle;
    /**
     * Исходный URL манифеста (с подписью), null для MP4. Находка на реальном сайте (probe5):
     * CDN phncdn подписывает каждый путь к файлу индивидуально (параметры h/e/f), поэтому подпись
     * одного ресурса НЕ переиспользуется для плейлиста/init/сегментов другого пути (HTTP 500).
     * Pump sniffера обновляет это поле до подписанного URL соответствующего события манифеста
     * и записывает каждый запрошенный браузером URL в {@link #recorded}.
     *
     * original manifest URL (with signature), null for MP4. Real-site finding (probe5):
     * phncdn CDN signs each file path individually (h/e/f params), so a signature from
     * one resource is NOT reusable for playlist/init/segments of another path (HTTP 500).
     * The sniffer pump upgrades this field to the signed URL of the matching manifest
     * event, and records every browser-requested URL in {@link #recorded}.
     */
    private volatile String masterUrl;

    /** filename/ключ → точный подписанный URL, который запросил браузер (карта по канонизированным ключам).
     *  filename/key → the exact signed URL the browser requested (canonicalized-key map).
     *  Записывается только pump-ом sniffера; читается download-воркером (без вызовов Playwright).
     *  Written only by the sniffer pump; read by the download worker (no Playwright calls). */
    private final Map<String, String> recorded = new ConcurrentHashMap<>();

    private volatile State state = State.FOUND;
    private final AtomicLong sizeBytes = new AtomicLong(0);
    private final AtomicInteger segmentsDone = new AtomicInteger(0);
    private final AtomicInteger segmentsTotal = new AtomicInteger(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private volatile long startedAtMs;
    private volatile String stage;
    private volatile String message;
    private volatile String outPath;
    private volatile String cookieHeader;
    private volatile String userAgent;
    private volatile String referer;
    private volatile String origin;
    /**
     * Записи YOUTUBE: сырой JSON ytInitialPlayerResponse, снятый на pump-стороне
     * через page.evaluate (подписные URL потоков живут здесь; никогда не логируются,
     * никогда не печатаются).
     * YOUTUBE records: the raw ytInitialPlayerResponse JSON captured pump-side via
     * page.evaluate (signed stream URLs live here; never logged, never printed).
     */
    private volatile String payload;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public SourceRecord(String canonicalUrl, Kind kind, String pageUrl, String pageTitle, long sizeBytes) {
        this(canonicalUrl, kind, pageUrl, pageTitle, sizeBytes, null);
    }

    public SourceRecord(String canonicalUrl, Kind kind, String pageUrl, String pageTitle, long sizeBytes, String masterUrl) {
        this.canonicalUrl = canonicalUrl;
        this.kind = kind;
        this.pageUrl = pageUrl;
        this.pageTitle = pageTitle;
        this.sizeBytes.set(sizeBytes);
        this.masterUrl = masterUrl;
    }

    public State state() { return state; }
    public void setState(State s) { state = s; }

    public String masterUrl() { return masterUrl; }
    /** pump-ом только: повышение до подписанного URL соответствующего события манифеста.
     *  pump-only: upgrade to the signed URL of a matching manifest event. */
    public void setMasterUrl(String url) { masterUrl = url; }

    /** pump-ом только: запомнить точный подписанный URL, который браузер извлёк по ключу имени файла.
     *  pump-only: remember the exact signed URL the browser fetched for a filename key. */
    public void putRecorded(String key, String url) { recorded.put(key, url); }

    /** поиск на стороне воркера URL, записанного браузером; null, если не запрашивался.
     *  worker-side lookup of a browser-recorded URL; null if never requested. */
    public String recordedUrl(String key) { return recorded.get(key); }

    /** представление только для чтения карты записанных URL (диагностика/тесты).
     *  read-only view of the recorded map (diagnostics/tests). */
    public java.util.Map<String, String> recordedView() {
        return java.util.Collections.unmodifiableMap(recorded);
    }

    /** Доступные высоты (p, px), по убыванию. Записывается только pump-ом sniffера.
     *  Available heights (p, px), descending. Written only by the sniffer pump. */
    public List<Integer> qualities() { return qualities; }

    /** Высоты по убыванию, без повторов, из манифеста. Pump-only.
     *  Sorted descending, distinct heights from the manifest. Pump-only. */
    public void setQualities(List<Integer> qs) {
        TreeSet<Integer> set = new TreeSet(Comparator.reverseOrder());
        for (int h : qs) {
            if (h > 0) {
                set.add(h);
            }
        }
        qualities = List.copyOf(set);
    }

    /** Максимальное доступное качество, 0 при неизвестном.
     *  Highest available quality, 0 if unknown. */
    public int maxQuality() {
        return qualities.isEmpty() ? 0 : qualities.get(0);
    }

    public long sizeBytes() { return sizeBytes.get(); }
    public void setSizeBytes(long v) { sizeBytes.set(v); }

    public int segmentsDone() { return segmentsDone.get(); }
    public int segmentsTotal() { return segmentsTotal.get(); }
    public void setSegments(int done, int total) {
        segmentsTotal.set(total);
        segmentsDone.set(done);
    }
    public void incSegmentsDone() { segmentsDone.incrementAndGet(); }

    public long bytesReceived() { return bytesReceived.get(); }
    public void addBytes(long n) { bytesReceived.addAndGet(n); }
    public void setBytesReceived(long v) { bytesReceived.set(v); }

    public long startedAtMs() { return startedAtMs; }
    public void setStartedAtMs(long v) { startedAtMs = v; }

    public String stage() { return stage; }
    public void setStage(String s) { stage = s; }

    public String message() { return message; }
    public void setMessage(String m) { message = m; }

    public String outPath() { return outPath; }
    public void setOutPath(String p) { outPath = p; }

    /** Заголовки запроса, снятые pump-ом sniffера (единственный вызывающий Playwright).
     *  Fetch headers snapshotted by the sniffer pump (sole Playwright caller). */
    public void setFetchHeaders(String cookieHeader, String userAgent) {
        this.cookieHeader = cookieHeader;
        this.userAgent = userAgent;
    }

    /** Полный снимок заголовков запроса, как отправил браузер (probe17: Referer/Cookie CDN).
     *  Full request-header snapshot as the browser sent it (probe17: CDN Referer/Cookie). */
    public void setFetchHeaders(String cookieHeader, String userAgent, String referer, String origin) {
        this.cookieHeader = cookieHeader;
        this.userAgent = userAgent;
        this.referer = referer;
        this.origin = origin;
    }
    public String cookieHeader() { return cookieHeader; }
    public String userAgent() { return userAgent; }
    public String referer() { return referer; }
    public String origin() { return origin; }

    /** pump-ом только: сырой JSON ytInitialPlayerResponse для записей YOUTUBE.
     *  pump-only: raw ytInitialPlayerResponse JSON for YOUTUBE records. */
    public void setPayload(String json) { payload = json; }

    /** на стороне воркера: JSON player response, null если не снят.
     *  worker-side: player response JSON, null if not captured. */
    public String payload() { return payload; }

    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }

    /** Неизменяемый снимок для отрисовки UI.
     *  Immutable snapshot for UI rendering. */
    public View snapshot() {
        return new View(canonicalUrl, kind, pageUrl, pageTitle, state, sizeBytes.get(),
                segmentsDone.get(), segmentsTotal.get(), bytesReceived.get(),
                System.currentTimeMillis() - startedAtMs, stage, message, outPath, qualities);
    }

    public record View(String canonicalUrl, Kind kind, String pageUrl, String pageTitle,
                       State state, long sizeBytes, int segmentsDone, int segmentsTotal,
                       long bytesReceived, long elapsedMs, String stage, String message,
                       String outPath, List<Integer> qualities) {
    }
}
