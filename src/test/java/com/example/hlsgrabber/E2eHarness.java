package com.example.hlsgrabber;

import com.example.hlsgrabber.browser.BrowserLauncher;
import com.example.hlsgrabber.browser.NetworkSniffer;
import com.example.hlsgrabber.config.Settings;
import com.example.hlsgrabber.download.DownloadQueue;
import com.example.hlsgrabber.ffmpeg.FfmpegRunner;
import com.example.hlsgrabber.manifest.SourceRecord;
import com.example.hlsgrabber.manifest.SourceRegistry;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Stage 2 end-to-end smoke: local HLS stand (spikes/r1-r2 static+gen) + the real product
 * pipeline (BrowserLauncher persistent context → NetworkSniffer → SourceRegistry →
 * DownloadQueue auto-enqueue → SegmentDownloader decrypt+concat → mp4), verified by ffprobe.
 *
 * Run:
 *   java -cp "target\hls-grabber-1.0-SNAPSHOT.jar;target\test-classes" \
 *       com.example.hlsgrabber.E2eHarness D:\Java\Downloader\spikes\r1-r2
 *
 * Exit 0 = PASS (mp4 downloaded, probed, playable), 1 = FAIL.
 */
public final class E2eHarness {

    static final int PORT = 8123;
    static final String BASE = "http://127.0.0.1:" + PORT;

    static Path ROOT, STATIC, GEN, OUT;

    public static void main(String[] args) throws Exception {
        ROOT = Paths.get(args.length > 0 ? args[0] : "D:\\Java\\Downloader\\spikes\\r1-r2");
        STATIC = ROOT.resolve("static");
        GEN = ROOT.resolve("gen");
        OUT = ROOT.resolve("e2e-out");
        Files.createDirectories(GEN);
        Files.createDirectories(OUT);
        // remove stale mp4 from previous runs so success detection is unambiguous
        try (var walk = Files.list(OUT)) {
            walk.filter(p -> p.toString().endsWith(".mp4")).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { }
            });
        }
        System.out.println("[E2E] root=" + ROOT);
        prepareKeys();
        HttpServer server = startServer();
        generateHls();
        generateBigMp4();

        Settings s = new Settings();
        s.downloadDir = OUT.toString();
        s.userAgent = null;          // browser default UA
        s.targetHeight = 1080;
        s.parallelSegments = 4;
        s.minSizeBytes = 1_048_576L;

        SourceRegistry registry = new SourceRegistry(s.minSizeBytes);
        registry.addListener(rec -> System.out.println("[E2E] state " + rec.kind + " "
                + rec.state() + " segs=" + rec.segmentsDone() + "/" + rec.segmentsTotal()
                + " stage=" + rec.stage() + " msg=" + rec.message() + " out=" + rec.outPath()));

        FfmpegRunner ffmpeg = new FfmpegRunner();
        DownloadQueue queue = new DownloadQueue(registry, s, ffmpeg);
        BrowserLauncher launcher = new BrowserLauncher(ROOT.resolve(".e2e-profile"), null, false);
        queue.setLauncher(launcher);

        NetworkSniffer sniffer = new NetworkSniffer(registry);
        sniffer.attach(launcher.page());
        System.out.println("[E2E] browser=" + launcher.channel() + ", opening player page");

        launcher.openPage(BASE + "/hls-play.html");

        // MP4 opens in a NEW tab: navigating pages().get(0) away from hls-play.html
        // stops hls.js playback and the segment flow (run 5 stalled at 8/15 exactly
        // at the second openPage navigation)
        // run 6: ctx.newPage() in a persistent context races page attachment —
        // navigate hits mainFrame=null. Retry navigate until the frame is ready.
        // run 7: ctx.newPage() can return null under the dispatch storm; retry
        // the newPage itself and null-guard every use of mp4Page.
        Page mp4Page = null;
        for (int i = 0; i < 5 && mp4Page == null; i++) {
            try {
                mp4Page = launcher.context().newPage();
            } catch (Exception e) {
                System.out.println("[E2E] mp4 newPage retry " + i + ": " + e.getMessage());
            }
            if (mp4Page == null) {
                Thread.sleep(1000);
            }
        }
        if (mp4Page == null) {
            System.out.println("[E2E] mp4 page unavailable — MP4 branch skipped");
        } else {
            sniffer.attach(mp4Page);
        }
        boolean mp4Navigated = false;
        for (int i = 0; i < 12 && !mp4Navigated && mp4Page != null; i++) {
            try {
                mp4Page.navigate(BASE + "/mp4-play.html",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                mp4Navigated = true;
            } catch (Exception e) {
                System.out.println("[E2E] mp4 nav retry " + i + ": " + e.getMessage());
                Thread.sleep(1500);
            }
        }

        // canonicals of the stand files (family-synthesized index-v1-a1.m3u8 record
        // from .ts segment counting must not hijack the target)
        final String hlsCanonical = BASE + "/out.m3u8";
        final String mp4Canonical = BASE + "/big.mp4";

        // unified poll: openPage can block tens of seconds under the dispatch storm,
        // so both branches are polled together after both navigations
        SourceRecord target = null;
        SourceRecord mp4Target = null;
        boolean targetTerminal = false;
        boolean mp4Terminal = false;
        boolean hlsEnqueued = false;
        boolean mp4Enqueued = false;
        long deadline = System.currentTimeMillis() + 150_000;
        long lastDrive = 0;
        while (System.currentTimeMillis() < deadline) {
            // headless stand playback stalls after ~2-3 segments (media pipeline
            // pauses in headless Chrome). Drive the player: play + seek-jump +30s
            // per 5s tick so hls.js loads every segment in order — exactly the
            // headful user-watching scenario the product relies on.
            long now = System.currentTimeMillis();
            if (now - lastDrive >= 5_000) {
                lastDrive = now;
                for (Page p : new Page[]{launcher.page(), mp4Page}) {
                    if (p == null) continue;
                    for (com.microsoft.playwright.Frame f : p.frames()) {
                        try {
                            f.evaluate(
                                    "() => { document.querySelectorAll('video').forEach(function(v){"
                                    + " try { v.play(); } catch (e) {}"
                                    + " var d=v.duration||0;"
                                    + " if (d>0 && isFinite(d) && d >= 10 && v.currentTime < d - 1) {"
                                    + "   try { v.currentTime = Math.min(d - 1, v.currentTime + 30); } catch (e) {}"
                                    + " }"
                                    + " });");
                        } catch (Exception ignored) { }
                    }
                }
            }
            for (SourceRecord r : registry.records()) {
                if (r.kind == SourceRecord.Kind.HLS && hlsCanonical.equals(r.canonicalUrl)) {
                    target = r;
                    SourceRecord.State st = r.state();
                    // button-driven enqueue (TZ 9 line 192): the harness emulates the user
                    // clicking "Скачать" once the source is detected and stays FOUND
                    if (st == SourceRecord.State.FOUND && !hlsEnqueued) {
                        hlsEnqueued = true;
                        System.out.println("[E2E] button-click HLS qualities=" + r.qualities()
                                + " url=" + r.canonicalUrl);
                        queue.enqueue(r);
                    }
                    if (st == SourceRecord.State.DONE
                            || st == SourceRecord.State.ERROR
                            || st == SourceRecord.State.CANCELLED) {
                        targetTerminal = true;
                    }
                }
                if (r.kind == SourceRecord.Kind.MP4 && mp4Canonical.equals(r.canonicalUrl)) {
                    mp4Target = r;
                    SourceRecord.State st = r.state();
                    if (st == SourceRecord.State.FOUND && !mp4Enqueued) {
                        mp4Enqueued = true;
                        System.out.println("[E2E] button-click MP4 url=" + r.canonicalUrl);
                        queue.enqueue(r);
                    }
                    if (st == SourceRecord.State.DONE
                            || st == SourceRecord.State.ERROR
                            || st == SourceRecord.State.CANCELLED) {
                        mp4Terminal = true;
                    }
                }
            }
            if (targetTerminal && mp4Terminal) {
                break;
            }
            Thread.sleep(250);
        }

        System.out.println("[E2E] closing browser, stopping server");
        System.out.println("[E2E] hls state=" + (target == null ? "NOT-DETECTED" : target.state())
                + " canonical=" + (target == null ? "-" : target.canonicalUrl)
                + " msg=" + (target == null ? "-" : target.message())
                + " out=" + (target == null ? "-" : target.outPath()));
        System.out.println("[E2E] mp4 state=" + (mp4Target == null ? "NOT-DETECTED" : mp4Target.state())
                + " canonical=" + (mp4Target == null ? "-" : mp4Target.canonicalUrl)
                + " msg=" + (mp4Target == null ? "-" : mp4Target.message())
                + " out=" + (mp4Target == null ? "-" : mp4Target.outPath()));
        launcher.close();
        server.stop(0);

        if (target == null) {
            System.out.println("[E2E] RESULT: FAIL - sniffing detected no HLS source within 90s");
            System.exit(1);
        }
        System.out.println("[E2E] final state=" + target.state()
                + " canonical=" + target.canonicalUrl
                + " segs=" + target.segmentsDone() + "/" + target.segmentsTotal()
                + " msg=" + target.message());
        if (target.state() != SourceRecord.State.DONE) {
            System.out.println("[E2E] RESULT: FAIL - state " + target.state());
            System.exit(1);
        }
        Path mp4 = Paths.get(target.outPath());
        long size = Files.size(mp4);
        String probe = ffmpeg.probe(mp4);
        System.out.println("[E2E] mp4=" + mp4 + " size=" + size + " bytes");
        System.out.println("[E2E] ffprobe:\n" + probe);
        boolean playable = size > 200_000 && probe.contains("duration")
                && probe.contains("h264") && probe.contains("aac");

        boolean mp4Ok = mp4Target != null && mp4Target.state() == SourceRecord.State.DONE
                && mp4Target.outPath() != null;
        long directSize = 0;
        String directProbe = "";
        if (mp4Ok) {
            Path direct = Paths.get(mp4Target.outPath());
            directSize = Files.size(direct);
            directProbe = ffmpeg.probe(direct);
            mp4Ok = directSize > 1_048_576L && directProbe.contains("duration")
                    && directProbe.contains("h264");
            System.out.println("[E2E] direct mp4=" + direct + " size=" + directSize);
        }
        System.out.println("[E2E] RESULT: " + (playable && mp4Ok ? "PASS"
                : "FAIL - playable=" + playable + " mp4Ok=" + mp4Ok));
        System.exit(playable && mp4Ok ? 0 : 1);
    }

    // ---------- local HLS stand (same layout as spikes/r1-r2) ----------

    static void prepareKeys() throws Exception {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        Files.write(GEN.resolve("key.bin"), key);
        String keyInfo = BASE + "/enckey.bin\n" + BASE + "/enckey.bin\n00000000000000000000000000000000\n";
        Files.write(GEN.resolve("key_info.txt"), keyInfo.getBytes(StandardCharsets.US_ASCII));
    }

    static void generateHls() throws Exception {
        String exe = org.bytedeco.javacpp.Loader.load(
                org.bytedeco.ffmpeg.ffmpeg.class).toString();
        ProcessBuilder pb = new ProcessBuilder(exe, "-y",
                "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "60",
                "-c:v", "h264_mf", "-b:v", "2000k", "-maxrate", "2500k", "-bufsize", "4000k",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k",
                "-f", "hls", "-hls_time", "4", "-hls_list_size", "0",
                "-hls_segment_type", "mpegts", "-hls_key_info_file", "key_info.txt", "out.m3u8");
        pb.directory(GEN.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        long segs;
        try (var walk = Files.list(GEN)) {
            segs = walk.filter(f -> f.toString().endsWith(".ts")).count();
        }
        System.out.println("[E2E] ffmpeg rc=" + rc + " segments=" + segs);
        if (rc != 0) {
            System.out.println(out);
            throw new java.io.IOException("ffmpeg failed rc=" + rc);
        }
    }

    /** Direct-download stand file: plain progressive MP4 > 1 MB (no HLS, no segments). */
    static void generateBigMp4() throws Exception {
        String exe = org.bytedeco.javacpp.Loader.load(
                org.bytedeco.ffmpeg.ffmpeg.class).toString();
        ProcessBuilder pb = new ProcessBuilder(exe, "-y",
                "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=300:sample_rate=48000",
                "-t", "45",
                "-c:v", "h264_mf", "-b:v", "1500k", "-maxrate", "2000k", "-bufsize", "3000k",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                "-c:a", "aac", "-b:a", "96k",
                "big.mp4");
        pb.directory(STATIC.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        long size = Files.exists(STATIC.resolve("big.mp4")) ? Files.size(STATIC.resolve("big.mp4")) : 0;
        System.out.println("[E2E] big.mp4 rc=" + rc + " size=" + size);
        if (rc != 0 || size < 1_048_576L) {
            System.out.println(out);
            throw new java.io.IOException("big.mp4 generation failed rc=" + rc + " size=" + size);
        }
    }

    static HttpServer startServer() throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", E2eHarness::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("[E2E] HTTP server on " + BASE);
        return server;
    }

    static void handle(HttpExchange ex) throws java.io.IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        String name = p.substring(1);
        Path f;
        if (name.equals("enckey.bin")) {
            f = GEN.resolve("key.bin");
        } else {
            f = STATIC.resolve(name);
            if (!Files.isRegularFile(f)) f = GEN.resolve(name);
        }
        if (!Files.isRegularFile(f)) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        byte[] body = Files.readAllBytes(f);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        String ct = switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "application/javascript";
            case "m3u8" -> "application/vnd.apple.mpegurl";
            case "ts" -> "video/mp2t";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
