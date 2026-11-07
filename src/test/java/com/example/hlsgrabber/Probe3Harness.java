package com.example.hlsgrabber;

import com.example.hlsgrabber.browser.BrowserLauncher;
import com.example.hlsgrabber.browser.NetworkSniffer;
import com.example.hlsgrabber.config.Settings;
import com.example.hlsgrabber.download.DownloadQueue;
import com.example.hlsgrabber.ffmpeg.FfmpegRunner;
import com.example.hlsgrabber.manifest.SourceRecord;
import com.example.hlsgrabber.manifest.SourceRegistry;
import com.microsoft.playwright.Page;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Probe3: replicates the FULL product GUI pipeline (BrowserLauncher + SourceRegistry +
 * NetworkSniffer + DownloadQueue + FfmpegRunner) on a real site, headful, with the user
 * profile, minus the Swing window.
 *
 * Playwright round-trips from the main thread fight with the sniffer pump during page-load
 * event storms (probe4: keyboard Space -> "Cannot find parent object request@...").
 * Play trigger is page.evaluate(video.play()) retried every 5 s — evaluate tolerates
 * the storm (R1 spike + pump evidence), keyboard/locator do not.
 *
 * Run:
 *   java -cp "target\hls-grabber-1.0-SNAPSHOT.jar;target\test-classes" \
 *       com.example.hlsgrabber.Probe3Harness <url> [timeoutSec]
 */
public class Probe3Harness {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "about:blank";
        int timeout = args.length > 1 ? Integer.parseInt(args[1]) : 120;

        Path out = Paths.get("D:/Java/Downloader/spikes/r1-r2/probe-out");
        Files.createDirectories(out);
        System.setProperty("hlsdir", out.toString());
        App.reconfigureLogging();

        Path profile = Paths.get("C:/Users/Home/Documents/.profile");

        Settings settings = new Settings();
        settings.downloadDir = out.toString();
        settings.minSizeBytes = 1_048_576;
        settings.parallelSegments = 6;
        settings.targetHeight = 720;  // mirror GUI acceptance settings

        FfmpegRunner ffmpeg = new FfmpegRunner();
        System.out.println("[P3] ffmpeg " + ffmpeg.version());

        BrowserLauncher launcher = new BrowserLauncher(profile, settings.userAgent, false);
        System.out.println("[P3] channel=" + launcher.channel());
        SourceRegistry registry = new SourceRegistry(settings.minSizeBytes);
        DownloadQueue queue = new DownloadQueue(registry, settings, ffmpeg);
        queue.setLauncher(launcher);
        Page page = launcher.page();
        NetworkSniffer sniffer = new NetworkSniffer(registry);
        sniffer.attach(page);

        launcher.openPage(url);
        System.out.println("[P3] page loaded t=0 events=" + sniffer.eventCount());
        // banner immediately so the user sees the hint before the first tick
        try {
            page.evaluate("() => { if (!document.getElementById('probe-banner')) { const d = document.createElement('div'); d.id = 'probe-banner'; d.style.cssText = 'position:fixed;top:8px;right:8px;z-index:2147483647;background:rgba(20,20,60,.92);color:#fff;font:14px monospace;padding:10px 14px;border-radius:8px;max-width:430px'; d.textContent = 'HLS Grabber (probe): the program drives the player (mouse click + Space, real trusted gestures). Press play on the player, Space, arrows, J/K. Do NOT close the window.'; document.body.appendChild(d); } }");
            System.out.println("[P3] banner injected");
        } catch (Exception e) {
            System.out.println("[P3] banner-fail: " + String.valueOf(e.getMessage()).replace('\n', ' '));
        }

        long start = System.currentTimeMillis();
        long deadline = start + timeout * 1000L;
        int attempts = 0;
        int lastRows = -1;
        boolean ended = false;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            int t = (int) ((System.currentTimeMillis() - start) / 1000);
            // probe6 stall: querySelector('video') on the main page hits the ad
            // pre-roll player (rs=0 t=0/0 forever); the real player lives in an
            // iframe. Play EVERY video in EVERY frame; ended = any frame's video
            // finished (duration>0, currentTime>=duration-0.7).
            // probe7: play-all-frames fires play on the real player (ad videos
            // 5s/14s ENDED), segs 0-5 fetched, then playback stalls at ~10-15s
            // (player pre-roll pauses the main video). Drive playback: jump
            // currentTime +30s per 5s tick (6x speed) on long videos so the
            // player loads every segment 0..N in order; the worker harvests
            // the recorded signed URLs. ended = longest video finished.
            String play;
            StringBuilder agg = new StringBuilder();
            // visible banner for the user once: the program drives the page
            try {
                if (attempts == 0) {
                    page.evaluate("() => { if (!document.getElementById('probe-banner')) { const d = document.createElement('div'); d.id = 'probe-banner'; d.style.cssText = 'position:fixed;top:8px;right:8px;z-index:2147483647;background:rgba(20,20,60,.92);color:#fff;font:14px monospace;padding:10px 14px;border-radius:8px;max-width:430px'; d.textContent = 'HLS Grabber (probe): the program drives the player (mouse click + Space, real trusted gestures). Press play on the player, Space, arrows, J/K. Do NOT close the window.'; document.body.appendChild(d); } }");
                }
            } catch (Exception e) {
                agg.append("[banner-fail:" + String.valueOf(e.getMessage()).replace('\n', ' ') + "]");
            }
            boolean pageClosed = false;
            try {
                com.microsoft.playwright.Frame main = page.mainFrame();
                // frame-aware player: 123av (and iframe players generally) keep the
                // video element inside an iframe, not the main frame. Pick the frame
                // whose video has a finite duration (the real player), fallback to
                // any frame with a video, fallback to main.
                com.microsoft.playwright.Frame playerFrame = main;
                String mainInfo = "novideo";
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    try {
                        Object r = f.evaluate("() => { var v=document.querySelector('video'); if(!v) return null;"
                                + " return (isFinite(v.duration) ? 1 : 0); }");
                        if (Boolean.TRUE.equals(r)) {
                            playerFrame = f;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
                try {
                    mainInfo = String.valueOf(playerFrame.evaluate(
                            "() => { var v=document.querySelector('video'); if(!v) return 'novideo';"
                            + " return 'main|' + (v.paused?'P':'R') + v.readyState + ' ' + Math.round(v.currentTime)"
                            + " + (isFinite(v.duration) ? '/' + Math.round(v.duration) : ''); }"));
                } catch (Exception e) {
                    mainInfo = "[info-fail:" + String.valueOf(e.getMessage()).replace('\n', ' ') + "]";
                }
                agg.append(mainInfo);
                boolean mainPlaying = mainInfo.contains("|R");
                boolean adActive = false;
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    try {
                        Object a = f.evaluate("() => { var r=false;"
                                + " document.querySelectorAll('video').forEach(function(v){"
                                + "  if(!v.paused && v.duration>0 && v.duration<=11) r=true; }); return r; }");
                        if (Boolean.TRUE.equals(a)) {
                            adActive = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
                agg.append(adActive ? "[ad]" : "[noad]");
                if (t >= 5 && !mainPlaying) {
                    try {
                        playerFrame.locator("video").first().press("Space");
                        agg.append("[SPACE-player]");
                    } catch (Exception e) {
                        agg.append("[space-fail:" + String.valueOf(e.getMessage()).replace('\n', ' ') + "]");
                    }
                }
                if (t >= 5 && !mainPlaying && !adActive) {
                    try {
                        double cx = 0, cy = 0;
                        boolean ok = false;
                        if (playerFrame == main) {
                            Object box = main.evaluate("() => { var v=document.querySelector('video');"
                                    + " var r=v.getBoundingClientRect();"
                                    + " return [Math.round(r.left + r.width/2), Math.round(r.top + r.height/2)]; }");
                            if (box instanceof java.util.List) {
                                java.util.List<?> p = (java.util.List<?>) box;
                                cx = ((Number) p.get(0)).doubleValue();
                                cy = ((Number) p.get(1)).doubleValue();
                                ok = true;
                            }
                        } else {
                            // iframe player: boundingBox of the <iframe> element is in
                            // top-level page coordinates, clickable by page.mouse()
                            com.microsoft.playwright.options.BoundingBox bb =
                                    playerFrame.frameElement().boundingBox();
                            if (bb != null && bb.width > 0 && bb.height > 0) {
                                cx = bb.x + bb.width / 2;
                                cy = bb.y + bb.height / 2;
                                ok = true;
                            }
                        }
                        if (ok) {
                            page.mouse().click(cx, cy);
                            agg.append("[CLICK-player " + Math.round(cx) + "," + Math.round(cy) + "]");
                        }
                    } catch (Exception e) {
                        agg.append("[click-fail:" + String.valueOf(e.getMessage()).replace('\n', ' ') + "]");
                    }
                }
                try {
                    Object r = playerFrame.evaluate("() => { var v=document.querySelector('video'); if(!v) return 'novideo';"
                            + " var d=v.duration, c=v.currentTime;"
                            + " if(d>=60 && c < d - 1 && !v.ended) { v.currentTime = Math.min(d - 1, c + 30); return 'seek'; }"
                            + " if(d>=60 && (v.ended || c >= d - 0.7)) return 'END'; return 'watch'; }");
                    agg.append(String.valueOf(r));
                } catch (Exception e) {
                    agg.append("[seek-fail:" + String.valueOf(e.getMessage()).replace('\n', ' ') + "]");
                }
                try {
                    main.evaluate("() => { window.scrollBy(0,400); }");
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("Target page, context or browser has been closed")) {
                    pageClosed = true;
                }
                agg.append("[gesture-fail:" + msg.replace('\n', ' ') + "]");
            }
            play = agg.toString();
            attempts++;
            if (play.contains("END")) {
                ended = true;
            }
            int rows = registry.records().size();
            System.out.println("[P3] t=" + t + "s events=" + sniffer.eventCount()
                    + " play[" + attempts + "]=" + play + " rows=" + rows);
            if (rows != lastRows) {
                printRows(registry);
                lastRows = rows;
            }
            if (pageClosed) {
                System.out.println("[P3] page closed by user — finishing t=" + t);
                break;
            }
            if (ended) {
                // playback finished: give the worker 60 s to drain recorded segments
                long drainEnd = System.currentTimeMillis() + 60_000;
                while (System.currentTimeMillis() < drainEnd) {
                    Thread.sleep(2_000);
                    int done = 0;
                    for (SourceRecord r : registry.records()) {
                        if (r.state() == SourceRecord.State.DONE) {
                            done++;
                        }
                    }
                    if (done > 0) {
                        break;
                    }
                }
                break;
            }
        }

        System.out.println("[P3] === final events=" + sniffer.eventCount() + " ===");
        printRows(registry);
        try (var s = Files.list(out)) {
            s.filter(p -> p.getFileName().toString().endsWith(".mp4")
                    || p.getFileName().toString().endsWith(".part"))
                    .forEach(p -> {
                        try {
                            System.out.println("[OUT] " + p.getFileName() + " " + Files.size(p));
                        } catch (Exception ignored) { }
                    });
        }
        launcher.close();
        System.out.println("[P3] done");
        System.exit(0);
    }

    private static void printRows(SourceRegistry registry) {
        for (SourceRecord r : registry.records()) {
            System.out.println("[ROW] kind=" + r.kind + " state=" + r.state()
                    + " title=" + r.pageTitle
                    + " seg=" + r.segmentsDone() + "/" + r.segmentsTotal()
                    + " bytes=" + r.bytesReceived()
                    + " stage=" + r.stage()
                    + (r.message() != null ? " msg=" + r.message() : "")
                    + (r.outPath() != null ? " out=" + r.outPath() : "")
                    + " url=" + r.canonicalUrl);
        }
    }
}
