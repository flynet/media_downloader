package com.example.hlsgrabber.browser;

import com.example.hlsgrabber.manifest.SourceRecord;
import com.example.hlsgrabber.manifest.SourceRegistry;
import com.example.hlsgrabber.util.UrlUtils;
import com.example.hlsgrabber.youtube.YouTubeApi;
import com.example.hlsgrabber.youtube.YouTubePlayer;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Attaches Playwright response listeners on the page, filters candidate URLs,
 * puts events into a queue; the pump-thread records them in SourceRegistry.
 *
 * Rules (R7 + probe3 findings):
 * - listener callbacks capture headers only: no body(), no round-trip calls
 *   on the dispatcher thread;
 * - pump makes NO network calls of its own (ctx.request().get() hung the pump
 *   during phncdn event storms -> TargetClosedError at close);
 * - pump is the ONLY Playwright round-trip caller in the GUI; main/UI threads
 *   must not touch page/locator while events flow (dispatcher protocol breaks);
 * - manifest bodies are fetched lazily in the download worker; record.masterUrl
 *   keeps the original signed URL (CDN signature params must not be canonicalized away);
 * - media segments (.m4s/.ts/.mp2t/.key) never become rows of their own; >=3 segment
 *   GETs from one directory (hls.js fetches the playlist in-page, Playwright sees
 *   only segment GETs) synthesize an HLS row with master = <dir>index-v1-a1.m3u8;
 * - phncdn signs each path individually (probe5: playlist/init/segments fetched with
 *   the signature of another path return HTTP 500). The pump records the exact signed
 *   URL of every manifest/segment/key event into the owning record's {@code recorded}
 *   map (filename -> URL); the worker downloads by recorded URLs, falling back to
 *   constructed ones on unsigned stands.
 */
public class NetworkSniffer {

    private static final Logger log = LoggerFactory.getLogger(NetworkSniffer.class);

    private final LinkedBlockingQueue<SniffEvent> queue = new LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger processed = new java.util.concurrent.atomic.AtomicInteger();
    private final SourceRegistry registry;
    private volatile boolean running = true;
    private volatile String cachedUa;

    public NetworkSniffer(SourceRegistry registry) {
        this.registry = registry;
    }

    /** Attach response listeners to the page; pump becomes the sole Playwright caller. */
    public void attach(Page page) {
        page.onResponse(this::onResponse);
        Thread pump = new Thread(() -> pumpLoop(page), "sniffer-pump");
        pump.setDaemon(true);
        pump.start();
    }

    public void stop() {
        running = false;
    }

    /** Number of sniff events processed by the pump (diagnostics for harnesses). */
    public int eventCount() {
        return processed.get();
    }

    // ---------- dispatch thread: headers only, no body(), no round-trips ----------

    private void onResponse(Response response) {
        try {
            String method = response.request().method();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                return;
            }
            int status = response.status();
            if (status < 200 || status > 299) {
                return;
            }
            String url = response.url();
            String lower = url.toLowerCase();
            String path = UrlUtils.pathOf(url).toLowerCase();
            String ct = response.headers().get("content-type");
            String ctLow = ct == null ? "" : ct.toLowerCase();
            // probe17: the browser-sent request headers (cookie/referer/origin/user-agent,
            // lowercase) are the only snapshot that passes CDN access checks on real sites
            Map<String, String> reqHeaders = null;
            try {
                reqHeaders = response.request().headers();
            } catch (Exception e) {
                log.debug("request headers capture failed: {}", e.toString());
            }

            if (ctLow.contains("mpegurl")) {
                queue.add(new SniffEvent(SniffEvent.Kind.MANIFEST, url, status, 0, reqHeaders, response));
            } else if (path.endsWith(".m3u8") || lower.contains(".m3u8?")) {
                queue.add(new SniffEvent(SniffEvent.Kind.MANIFEST, url, status, 0, reqHeaders, response));
            } else if (ctLow.contains("dash+xml") || path.endsWith(".mpd")) {
                queue.add(new SniffEvent(SniffEvent.Kind.DASH, url, status, 0, reqHeaders, response));
            } else if (UrlUtils.isSegmentPath(path)) {
                // Pornhub serves .m4s with Content-Type video/mp4 — classify by path first
                queue.add(new SniffEvent(SniffEvent.Kind.SEGMENT, url, status, 0, reqHeaders, response));
            } else if (ctLow.startsWith("video/mp4") || path.endsWith(".mp4")) {
                queue.add(new SniffEvent(SniffEvent.Kind.MP4, url, status,
                        parseLong(response.headers().get("content-length")), reqHeaders, response));
            }
        } catch (Exception e) {
            log.debug("onResponse failed: {}", e.toString());
        }
    }

    // ---------- pump thread: the only Playwright round-trip caller ----------

    private void pumpLoop(Page page) {
        Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        String lastYouTubeId = null;
        long lastYouTubeAttempt = 0;
        while (running) {
            // Force a round-trip so the Playwright dispatcher delivers the queued response
            // events. Events fire only during API calls; queue.poll alone starves the sniffer
            // (headless + headful smoke: events landed only at browser close).
            try {
                page.evaluate("1");
            } catch (Exception e) {
                // known Java dispatcher race "Object doesn't exist: response@..." during event
                // storms: transient, dispatcher recovers on the next round-trip. Keep pumping.
                log.debug("pump round-trip race: {}", e.getMessage());
                if (page.isClosed()) {
                    return;
                }
            }
            // YouTube: the web player serves media over server-side ABR, so the response
            // listener never sees a downloadable URL. The pump asks the inner-tube player
            // API (VISIONOS client) from the page itself — the only caller allowed to
            // round-trip to the page.
            try {
                String ytId = UrlUtils.youtubeVideoId(page.url());
                if (ytId != null && !ytId.equals(lastYouTubeId)) {
                    long now = System.currentTimeMillis();
                    if (now - lastYouTubeAttempt > 3000) {
                        lastYouTubeAttempt = now;
                        // retry until the player response is captured: the page may still be
                        // loading (no visitorData yet -> the inner-tube call is bot-checked)
                        if (captureYouTube(page, ytId)) {
                            lastYouTubeId = ytId;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("youtube capture failed: {}", e.toString());
            }
            SniffEvent ev;
            try {
                ev = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (ev == null) {
                continue;
            }
            processed.incrementAndGet();
            if (page.isClosed()) {
                return;
            }
            try {
                process(page, ev, seen);
            } catch (Exception e) {
                log.warn("sniffer pump failed on {}: {}", ev.url(), e.toString());
                if (page.isClosed()) {
                    return;
                }
            }
        }
    }

    /**
     * YouTube capture: asks the inner-tube player API from inside the page (cookies +
     * visitorData of the watch page) and registers a YOUTUBE record with the quality
     * list. The raw player response is stored in the record and consumed by the
     * download worker (no signed URL ever reaches the log).
     *
     * @return true when the record exists (captured or already registered)
     */
    private boolean captureYouTube(Page page, String videoId) {
        String json = (String) page.evaluate(YouTubeApi.PAGE_FETCH_SCRIPT, Map.of(
                "key", YouTubeApi.API_KEY,
                "clientName", YouTubeApi.CLIENT_NAME,
                "clientVersion", YouTubeApi.CLIENT_VERSION,
                "payloadTemplate", YouTubeApi.payloadTemplate(videoId),
                "visitor", ""));
        if (json == null || json.isBlank()) {
            log.debug("YouTube: пустой ответ player API для {}", videoId);
            return false;
        }
        YouTubePlayer.Player player = YouTubePlayer.parse(json);
        if (!player.ok()) {
            log.info("YouTube {}: {}", videoId, YouTubePlayer.reasonOf(player));
            return false;
        }
        String canonical = "yt:" + videoId;
        SourceRecord existing = registry.get(canonical);
        if (existing != null) {
            existing.setPayload(json);
            existing.setQualities(YouTubePlayer.qualities(player));
            registry.notifyChanged(existing);
            return true;
        }
        String pageUrl = page.url();
        SourceRecord rec = new SourceRecord(canonical, SourceRecord.Kind.YOUTUBE, pageUrl,
                player.title(), 0, null);
        rec.setPayload(json);
        rec.setQualities(YouTubePlayer.qualities(player));
        rec.setFetchHeaders(cookieHeaderOf(page, pageUrl), userAgentOf(page), pageUrl,
                UrlUtils.originOf(pageUrl));
        registry.add(rec);
        log.info("Найден YouTube: {} — {}", videoId, player.title());
        return true;
    }

    private void process(Page page, SniffEvent ev, Map<String, Map<String, String>> seen) {
        switch (ev.kind()) {
            case MANIFEST -> addRecord(page, ev, SourceRecord.Kind.HLS, 0, seen);
            case DASH -> addRecord(page, ev, SourceRecord.Kind.DASH, 0, seen);
            case MP4 -> addRecord(page, ev, SourceRecord.Kind.MP4, ev.sizeHint(), seen);
            case SEGMENT -> handleSegmentFamily(ev, page, seen);
        }
    }

    private void addRecord(Page page, SniffEvent ev, SourceRecord.Kind kind, long sizeHint,
                           Map<String, Map<String, String>> seen) {
        String url = ev.url();
        String canonical = UrlUtils.canonicalize(url);
        String filename = UrlUtils.lastSegment(url);
        SourceRecord existing = registry.get(canonical);
        if (existing != null) {
            // same file path re-requested: keep one row; upgrade to the fresh signed URL
            if (kind == SourceRecord.Kind.HLS) {
                existing.setMasterUrl(url);
                mergeQualities(ev, existing);
            }
            existing.putRecorded(filename, url);
            return;
        }
        String dir = UrlUtils.canonicalDir(url);
        SourceRecord owner = findDirOwner(dir);
        if (owner != null) {
            // dir is owned by an HLS record: playlist/segment/key GETs and the init
            // segment (.mp4 named init*) wire into its recorded map, never into rows;
            // plain .mp4 files in the same dir (stand big.mp4, trailers) stay rows
            String lowerPath = UrlUtils.pathOf(url).toLowerCase();
            boolean initLike = lowerPath.endsWith(".mp4") && filename.toLowerCase().startsWith("init");
            if (kind == SourceRecord.Kind.HLS) {
                owner.putRecorded(filename, url);
                mergeQualities(ev, owner);
                return;
            }
            if (initLike) {
                owner.putRecorded(filename, url);
                return;
            }
        }
        String pageUrl = page.url();
        String title = pageTitleOf(page);
        // probe17: prefer the exact headers the browser sent for THIS url (CDN Referer +
        // CDN-domain Cookie from the player iframe); top-page snapshot is the fallback
        String cookie = ev.header("cookie") != null ? ev.header("cookie")
                : cookieHeaderOf(page, pageUrl);
        String ua = ev.header("user-agent") != null ? ev.header("user-agent")
                : userAgentOf(page);
        String referer = ev.header("referer") != null ? ev.header("referer") : pageUrl;
        String origin = ev.header("origin") != null ? ev.header("origin") : null;
        SourceRecord rec = new SourceRecord(canonical, kind, pageUrl, title, sizeHint,
                kind == SourceRecord.Kind.HLS ? url : null);
        rec.setFetchHeaders(cookie, ua, referer, origin);
        registry.add(rec);
        log.info("Найден {}: {}{}", kind, url, title == null || title.isBlank() ? "" : " — " + title);
        if (kind == SourceRecord.Kind.HLS) {
            rec.putRecorded(filename, url);
            mergeQualities(ev, rec);
        }
    }

    /**
     * Pump-side quality extraction (the pump is the sole Playwright round-trip caller):
     * fetch the manifest body once via response.text() and merge the available heights
     * into the record. Master playlists declare variants as #EXT-X-STREAM-INF:RESOLUTION=WxH;
     * per-quality playlist files on real sites carry RESOLUTION in comment lines.
     */
    private void mergeQualities(SniffEvent ev, SourceRecord rec) {
        String text;
        try {
            text = ev.response() == null ? null : ev.response().text();
        } catch (Exception e) {
            log.debug("manifest body for qualities unavailable: {}", e.toString());
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        List<Integer> found = extractQualities(text);
        if (found.isEmpty()) {
            return;
        }
        List<Integer> before = rec.qualities();
        List<Integer> merged = new ArrayList<>(before);
        merged.addAll(found);
        rec.setQualities(merged);
        List<Integer> after = rec.qualities();
        if (!after.equals(before)) {
            String ps = after.stream().map(h -> h + "p").collect(Collectors.joining(", "));
            log.info("Качества {}: {} — {}", rec.kind, ps, rec.canonicalUrl);
        }
    }

    private static List<Integer> extractQualities(String text) {
        Matcher m = RES_ATTR.matcher(text);
        List<Integer> heights = new ArrayList<>();
        while (m.find()) {
            try {
                heights.add(Integer.parseInt(m.group(2)));
            } catch (NumberFormatException e) {
                // ignore malformed entry
            }
        }
        return heights;
    }

    private static final Pattern RES_ATTR =
            Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Segment family detection: >=3 segment GETs from one directory synthesize an HLS
     * row (hls.js fetches the playlist in-page, Playwright sees only segment GETs).
     * Every segment/key GET in an owned directory is recorded into the owner's
     * {@code recorded} map for signed-URL download by the worker.
     */
    private void handleSegmentFamily(SniffEvent ev, Page page, Map<String, Map<String, String>> seen) {
        String url = ev.url();
        int sch = url.indexOf("//");
        if (sch < 0) {
            return;
        }
        String rest = url.substring(sch + 2);
        int dirEnd = rest.lastIndexOf('/');
        if (dirEnd < 0) {
            return;
        }
        String dirPrefix = url.substring(0, sch + 2) + rest.substring(0, dirEnd + 1);
        String filename = UrlUtils.lastSegment(url);
        if (filename.isEmpty()) {
            return;
        }
        Map<String, String> m = seen.computeIfAbsent(dirPrefix, k -> new ConcurrentHashMap<>());
        m.put(filename, url);
        SourceRecord owner = findDirOwner(dirPrefix);
        if (owner != null) {
            owner.putRecorded(filename, url);
            return;
        }
        if (m.size() == 3) {
            String canonical = dirPrefix + "index-v1-a1.m3u8";
            if (registry.get(canonical) != null) {
                return;
            }
            int q = url.indexOf('?');
            String masterUrl = canonical + (q >= 0 ? url.substring(q) : "");
            String pageUrl = page.url();
            SourceRecord rec = new SourceRecord(canonical, SourceRecord.Kind.HLS, pageUrl,
                    pageTitleOf(page), 0, masterUrl);
            // probe17: headers as the browser sent them for the segment request
            rec.setFetchHeaders(ev.header("cookie"), ev.header("user-agent"),
                    ev.header("referer") != null ? ev.header("referer") : pageUrl,
                    ev.header("origin"));
            registry.add(rec);
            for (Map.Entry<String, String> e : m.entrySet()) {
                rec.putRecorded(e.getKey(), e.getValue());
            }
            log.info("Family-детект: {} сегментных GET из {} → HLS-запись", m.size(), dirPrefix);
        }
    }

    /** HLS record owning a canonical directory (playlist/init/segments wire target). */
    private SourceRecord findDirOwner(String dir) {
        for (SourceRecord r : registry.records()) {
            if (r.kind == SourceRecord.Kind.HLS
                    && dir.equals(UrlUtils.canonicalDir(r.canonicalUrl))) {
                return r;
            }
        }
        return null;
    }

    private static String pageTitleOf(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cookieHeaderOf(Page page, String pageUrl) {
        try {
            List<Cookie> cookies = page.context().cookies(pageUrl);
            StringBuilder sb = new StringBuilder();
            for (Cookie c : cookies) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(c.name).append('=').append(c.value);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String userAgentOf(Page page) {
        String ua = cachedUa;
        if (ua != null) {
            return ua;
        }
        try {
            ua = page.evaluate("() => navigator.userAgent").toString();
            cachedUa = ua;
        } catch (Exception e) {
            return null;
        }
        return ua;
    }

    private static long parseLong(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
