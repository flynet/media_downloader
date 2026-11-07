package com.example.hlsgrabber.manifest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A detected source (HLS manifest, DASH manifest, direct MP4) and its state.
 * Thread-safe mutable fields; UI reads via snapshot() (immutable view).
 */
public final class SourceRecord {

    /** Available video heights (p, px), descending, as reported by the manifest. */
    private volatile List<Integer> qualities = List.of();

    public enum Kind { HLS, DASH, MP4, YOUTUBE }
    public enum State { FOUND, QUEUED, DOWNLOADING, DONE, ERROR, CANCELLED }

    public final String canonicalUrl;
    public final Kind kind;
    public final String pageUrl;
    public final String pageTitle;
    /**
     * original manifest URL (with signature), null for MP4. Real-site finding (probe5):
     * phncdn CDN signs each file path individually (h/e/f params), so a signature from
     * one resource is NOT reusable for playlist/init/segments of another path (HTTP 500).
     * The sniffer pump upgrades this field to the signed URL of the matching manifest
     * event, and records every browser-requested URL in {@link #recorded}.
     */
    private volatile String masterUrl;

    /** filename/key → the exact signed URL the browser requested (canonicalized-key map).
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
    /** pump-only: upgrade to the signed URL of a matching manifest event. */
    public void setMasterUrl(String url) { masterUrl = url; }

    /** pump-only: remember the exact signed URL the browser fetched for a filename key. */
    public void putRecorded(String key, String url) { recorded.put(key, url); }

    /** worker-side lookup of a browser-recorded URL; null if never requested. */
    public String recordedUrl(String key) { return recorded.get(key); }

    /** read-only view of the recorded map (diagnostics/tests). */
    public java.util.Map<String, String> recordedView() {
        return java.util.Collections.unmodifiableMap(recorded);
    }

    /** Available heights (p, px), descending. Written only by the sniffer pump. */
    public List<Integer> qualities() { return qualities; }

    /** Sorted descending, distinct heights from the manifest. Pump-only. */
    public void setQualities(List<Integer> qs) {
        TreeSet<Integer> set = new TreeSet(Comparator.reverseOrder());
        for (int h : qs) {
            if (h > 0) {
                set.add(h);
            }
        }
        qualities = List.copyOf(set);
    }

    /** Highest available quality, 0 if unknown. */
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

    /** Fetch headers snapshotted by the sniffer pump (sole Playwright caller). */
    public void setFetchHeaders(String cookieHeader, String userAgent) {
        this.cookieHeader = cookieHeader;
        this.userAgent = userAgent;
    }

    /** Full request-header snapshot as the browser sent it (probe17: CDN Referer/Cookie). */
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

    /** pump-only: raw ytInitialPlayerResponse JSON for YOUTUBE records. */
    public void setPayload(String json) { payload = json; }

    /** worker-side: player response JSON, null if not captured. */
    public String payload() { return payload; }

    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }

    /** Immutable snapshot for UI rendering. */
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
