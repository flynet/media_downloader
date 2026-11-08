package com.flynet.hlsgrabber.ui.fx;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.browser.NetworkSniffer;
import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.microsoft.playwright.Page;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI bootstrap (FX mirror of App.runGui): theme init, log panel wired to the
 * logback appender, ffmpeg check, queue, window, folder picker over the shown
 * window (cancel = exit 0), browser launch on a daemon thread, Mica title bar.
 */
public final class FxGuiApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Settings settings = Settings.load();
        ThemeBridge.init(settings.theme);
        ThemeBridge.setSaveHook(name -> {
            settings.theme = name;
            settings.save();
        });

        LogFx logPanel = new LogFx(() -> Path.of(settings.downloadDir));
        GuiLogAppender.setSink(logPanel::appendLine);

        FfmpegRunner ffmpeg;
        try {
            ffmpeg = new FfmpegRunner();
        } catch (Exception e) {
            logPanel.appendLine("FFmpeg unavailable: " + e.getMessage());
            Platform.exit();
            System.exit(1);
            return;
        }

        SourceRegistry registry = new SourceRegistry(settings.minSizeBytes);
        DownloadQueue queue = new DownloadQueue(registry, settings, ffmpeg);

        AtomicReference<BrowserLauncher> launcher = new AtomicReference<>();
        queue.setIdleHook(() -> {
            BrowserLauncher l = launcher.get();
            if (l != null) {
                l.close();
            }
            System.exit(0);
        });

        SourceListFx list = new SourceListFx(registry, queue, launcher);
        FxMainWindow w = new FxMainWindow(stage, list, logPanel, settings, queue);
        w.show();

        // folder picker as a dialog over the shown window (Swing mirror: cancel = exit 0)
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Download folder");
        if (settings.downloadDir != null) {
            File d = new File(settings.downloadDir);
            if (d.isDirectory()) {
                chooser.setInitialDirectory(d);
            }
        }
        File sel = chooser.showDialog(stage);
        if (sel == null) {
            Platform.exit();
            System.exit(0);
            return;
        }
        settings.downloadDir = sel.getPath();
        settings.save();
        w.setFolder(settings.downloadDir);
        System.setProperty("hlsdir", settings.downloadDir);
        reconfigureLogging();
        logPanel.appendLine("ffmpeg: " + ffmpeg.version());
        logPanel.appendLine("download folder: " + settings.downloadDir);

        Thread launchThread = new Thread(() -> {
            try {
                w.statusLine("launching the browser… (first run may take 10–20 s)");
                logPanel.appendLine("launching the browser (headful, persistent profile)…");
                BrowserLauncher l = new BrowserLauncher(
                        Path.of(settings.downloadDir, ".profile"), settings.userAgent, false);
                launcher.set(l);
                queue.setLauncher(l);
                NetworkSniffer sniffer = new NetworkSniffer(registry);
                for (Page p : l.context().pages()) {
                    sniffer.attach(p);
                }
                // user opens a site in a new tab → attach the sniffer so its rows appear
                l.context().onPage(page -> {
                    sniffer.attach(page);
                    String u;
                    try {
                        u = page.url();
                    } catch (Exception e) {
                        u = "?";
                    }
                    logPanel.appendLine("new tab: " + u);
                });
                logPanel.appendLine("browser launched: " + l.channel());
                w.statusLine("browser ready (" + l.channel()
                        + ") — enter the URL and click \"Open URL\"");
                list.setOpenEnabled(true);
            } catch (Exception e) {
                logPanel.appendLine("browser not launched: " + e);
                w.statusLine("browser not launched — check Chrome/Edge and your antivirus");
            }
        }, "browser-launch");
        launchThread.setDaemon(true);
        launchThread.start();

        MicaHelper.apply(stage, ThemeBridge.mode() == ThemeBridge.Mode.DARK);
    }

    /** Re-run logback config after System.setProperty("hlsdir", ...) so
     * the file appender lands in the downloads folder. */
    private static void reconfigureLogging() {
        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.reset();
            JoranConfigurator j = new JoranConfigurator();
            j.setContext(ctx);
            j.doConfigure(FxGuiApp.class.getResourceAsStream("/logback.xml"));
        } catch (Exception e) {
            System.err.println("logback reconfigure failed: " + e);
        }
    }
}
