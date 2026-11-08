package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.browser.NetworkSniffer;
import com.flynet.hlsgrabber.manifest.SourceRecord;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stand-focused sniffer check (headless via BrowserLauncher): real product
 * NetworkSniffer + pump on the local HLS stand; prints rows, masterUrl, recorded
 * map, and recorded lookups for stand segments/keys. Reuses existing gen/static
 * files from E2E runs.
 */
public class StandCheck {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : "D:\\Java\\Downloader\\spikes\\r1-r2");
        E2eHarness.STATIC = root.resolve("static");
        E2eHarness.GEN = root.resolve("gen");
        System.out.println("[SC] static=" + E2eHarness.STATIC + " gen=" + E2eHarness.GEN
                + " m3u8=" + java.nio.file.Files.isRegularFile(E2eHarness.GEN.resolve("out.m3u8"))
                + " big.mp4=" + java.nio.file.Files.isRegularFile(E2eHarness.STATIC.resolve("big.mp4")));

        HttpServer server = E2eHarness.startServer();
        try {
            BrowserLauncher launcher = new BrowserLauncher(root.resolve(".stand-profile"), null, false);
            System.out.println("[SC] channel=" + launcher.channel());
            Page page = launcher.page();
            SourceRegistry registry = new SourceRegistry(1_048_576L);
            NetworkSniffer sniffer = new NetworkSniffer(registry);
            sniffer.attach(page);
            launcher.openPage(E2eHarness.BASE + "/hls-play.html");

            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 60_000) {
                Thread.sleep(5000);
                // drive playback headful: jump currentTime +30s per tick so hls.js
                // loads every segment in order (headful page start is ~25s)
                StringBuilder agg = new StringBuilder();
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    try {
                        Object r = f.evaluate(
                                "() => { var n=0, durs=[];"
                                + " document.querySelectorAll('video').forEach(function(v){ n++;"
                                + " try { v.play(); } catch (e) {}"
                                + " var d=v.duration||0;"
                                + " if (d>0 && isFinite(d)) {"
                                + "   var c=v.currentTime;"
                                + "   durs.push(Math.round(d) + '/' + Math.round(c));"
                                + "   if (d >= 60 && c < d - 1) { try { v.currentTime = Math.min(d - 1, c + 30); } catch (e) {} }"
                                + " }"
                                + " });"
                                + " return 'n=' + n + ' ' + durs.join(' '); }");
                        agg.append(String.valueOf(r));
                    } catch (Exception e) {
                        agg.append("[frame-fail]");
                    }
                }
                System.out.println("[SC] t=" + ((System.currentTimeMillis() - t0) / 1000)
                        + "s events=" + sniffer.eventCount() + " rows=" + registry.records().size()
                        + " play=" + agg);
            }

            System.out.println("[SC] rows=" + registry.records().size());
            for (SourceRecord r : registry.records()) {
                System.out.println("[SC] row kind=" + r.kind + " canonical=" + r.canonicalUrl
                        + " state=" + r.state() + " masterUrl=" + r.masterUrl()
                        + " recorded=" + r.recordedView());
                if (r.kind == SourceRecord.Kind.HLS) {
                    String[] keys = {"out0.ts", "out1.ts", "out2.ts", "out3.ts",
                            "out7.ts", "out14.ts", "key.bin", "enckey.bin"};
                    for (String k : keys) {
                        System.out.println("[SC] lookup " + k + " -> " + r.recordedUrl(k));
                    }
                }
            }
            launcher.close();
        } finally {
            server.stop(0);
        }
        System.out.println("[SC] done");
        System.exit(0);
    }
}
