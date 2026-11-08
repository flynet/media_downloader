package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.browser.NetworkSniffer;
import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.microsoft.playwright.Page;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;

/**
 * Entry point.
 * CLI: --url <url> [out.mp4], --ffmpeg-selftest.
 * GUI: launches the JavaFX application (FxGuiApp).
 */
public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--url")) {
            System.exit(runCli(args));
            return;
        }
        if (args.length > 0 && args[0].equals("--ffmpeg-selftest")) {
            try {
                System.out.println(new FfmpegRunner().version());
            } catch (Exception e) {
                System.out.println("FFMPEG_UNAVAILABLE: " + e.getMessage());
                System.exit(1);
            }
            System.exit(0);
        }
        javafx.application.Application.launch(com.flynet.hlsgrabber.ui.fx.FxGuiApp.class, new String[0]);
    }

    private static int runCli(String[] args) throws Exception {
        String url = args[1];
        String out = args.length > 2 ? args[2] : "out.mp4";
        Settings s = Settings.load();
        s.targetHeight = 1080;
        FfmpegRunner ff = new FfmpegRunner();
        SourceRegistry reg = new SourceRegistry(s.minSizeBytes);
        DownloadQueue q = new DownloadQueue(reg, s, ff);
        BrowserLauncher l = new BrowserLauncher(Path.of(s.downloadDir, ".profile"), s.userAgent, true);
        q.setLauncher(l);
        NetworkSniffer sn = new NetworkSniffer(reg);
        Page page = l.page();
        sn.attach(page);
        page.navigate(url);
        java.util.List<com.flynet.hlsgrabber.manifest.SourceRecord> recs = reg.records();
        if (recs.isEmpty()) {
            System.out.println("no media found, waiting 20 s");
            Thread.sleep(20000);
            recs = reg.records();
        }
        for (var r : recs) q.enqueue(r);
        while (q.hasActiveTasks()) {
            Thread.sleep(500);
        }
        l.close();
        return 0;
    }

    /** logging configured from logback.xml (hlsdir property is set after the folder is chosen) */
    static void reconfigureLogging() {
        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.reset();
            JoranConfigurator jc = new JoranConfigurator();
            jc.setContext(ctx);
            java.io.InputStream in = App.class.getResourceAsStream("/logback.xml");
            if (in == null) {
                throw new IllegalStateException("resource /logback.xml not found on classpath");
            }
            jc.doConfigure(in);
            in.close();
        } catch (Exception e) {
            System.err.println("logging configured by defaults: " + e);
        }
    }
}
