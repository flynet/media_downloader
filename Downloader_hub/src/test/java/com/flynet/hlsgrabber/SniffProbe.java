package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sniffing probe: opens a real page in the product BrowserLauncher (headful, persistent
 * context) and dumps EVERY GET/HEAD response seen by the context (method, status,
 * content-type, content-length, content-range, url, frame) to stdout. Diagnostic for
 * real-site detection (playlists served with non-standard content-types, Range/206
 * media requests, segment-only traffic).
 *
 * Run:
 *   java -cp "target\hls-grabber-1.0-SNAPSHOT.jar;target\test-classes" \
 *       com.flynet.hlsgrabber.SniffProbe <url>
 */
public class SniffProbe {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "about:blank";
        Path profile = Paths.get("D:/Java/Downloader/spikes/r1-r2/.probe-profile");
        List<String> lines = new CopyOnWriteArrayList<>();
        BrowserLauncher launcher = new BrowserLauncher(profile, null, false);
        BrowserContext ctx = launcher.context();
        System.out.println("[PROBE] channel=" + launcher.channel());

        ctx.onResponse(r -> {
            try {
                String m = r.request().method();
                if (!m.equals("GET") && !m.equals("HEAD")) return;
                int st = r.status();
                String ct = r.headerValue("content-type");
                String cl = r.headerValue("content-length");
                String cr = r.headerValue("content-range");
                String frameUrl = r.request().frame().url();
                lines.add(m + " " + st + " ct=" + ct + " cl=" + cl + " cr=" + cr
                        + " frame=" + frameUrl + " url=" + r.url());
            } catch (Exception e) {
                lines.add("ERR " + e.getMessage());
            }
        });

        launcher.openPage(url);
        System.out.println("[PROBE] page loaded, starting playback...");
        Thread.sleep(2500);
        Page page = launcher.page();
        boolean played = false;
        try {
            page.click("video", new Page.ClickOptions().setTimeout(6000));
            System.out.println("[PROBE] clicked video element");
            played = true;
        } catch (Exception e) {
            System.out.println("[PROBE] video click failed: " + e.getMessage());
        }
        if (!played) {
            try {
                page.keyboard().press("Space");
                System.out.println("[PROBE] pressed Space");
            } catch (Exception e) {
                System.out.println("[PROBE] Space press failed: " + e.getMessage());
            }
        }
        System.out.println("[PROBE] waiting 40s for media traffic...");
        Thread.sleep(40_000);

        for (String l : lines) {
            System.out.println(l);
        }
        System.out.println("[PROBE] total=" + lines.size());
        launcher.close();
        System.out.println("[PROBE] done");
        System.exit(0);
    }
}
