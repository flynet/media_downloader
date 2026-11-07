package com.flynet.hlsgrabber;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.browser.NetworkSniffer;
import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.download.HttpFetcher;
import com.flynet.hlsgrabber.download.SegmentDownloader;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.flynet.hlsgrabber.ui.LogPanel;
import com.flynet.hlsgrabber.ui.MainWindow;
import com.flynet.hlsgrabber.ui.SourceListPanel;
import com.flynet.hlsgrabber.ui.SwingLogAppender;
import com.flynet.hlsgrabber.util.FileNames;
import com.flynet.hlsgrabber.util.UrlUtils;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Точка входа. GUI-режим (--gui): выбор папки, браузер, двухпанельное окно.
 * CLI-режим обеспечивает проверку ядра Stage 1: {@code App --url <m3u8> --out <folder>}.
 *
 * Коды выхода: 0 = успех, 1 = ошибка загрузки, 2 = usage, 3 = пропущено фильтрами
 * (live/DRM/нет 1080p).
 * Entry point. GUI mode (--gui): folder picker, browser, two-pane window.
 * CLI mode powers Stage 1 core verification: {@code App --url <m3u8> --out <folder>}.
 *
 * Exit codes: 0 = success, 1 = download error, 2 = usage, 3 = skipped by filters (live/DRM/no 1080p).
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o == null) {
            System.out.println("usage: App --url <m3u8|mp4> --out <folder> [--name X] "
                    + "[--referer R] [--ua U] [--cookie C] [--parallel N] [--ffmpeg-selftest]");
            System.out.println("       App --gui");
            System.exit(2);
            return;
        }

        if (o.gui) {
            runGui();
            return;
        }

        if (o.ffmpegSelftest) {
            FfmpegRunner f = new FfmpegRunner();
            System.out.println("ffmpeg: " + f.version());
            System.exit(0);
        }

        Settings settings = Settings.load();
        if (o.parallel != null) {
            settings.parallelSegments = o.parallel;
        }
        Path outDir = Path.of(o.out);
        Files.createDirectories(outDir);
        System.setProperty("hlsdir", outDir.toString());
        reconfigureLogging();

        FfmpegRunner ffmpeg = new FfmpegRunner();
        log.info("FFmpeg: {}", ffmpeg.version());

        HttpFetcher.Headers headers = new HttpFetcher.Headers(o.referer, originOf(o.referer),
                o.ua, o.cookie);
        HttpFetcher fetcher = new HttpFetcher(headers);
        SegmentDownloader downloader = new SegmentDownloader(fetcher, ffmpeg, settings.parallelSegments);

        String name = o.name != null ? o.name : defaultName(o.url);
        try {
            Path out;
            if (UrlUtils.isDirectMp4Candidate(o.url, null) && !o.url.toLowerCase().contains(".m3u8")) {
                log.info("Прямой MP4 / direct MP4: {}", o.url);
                out = downloadDirect(fetcher, o.url, outDir, name);
            } else {
                log.info("Запуск HLS-загрузки / starting HLS download: {}", o.url);
                out = downloader.runMaster(o.url, outDir, name, settings.targetHeight);
            }
            log.info("Готово / done: {}", out);
            System.out.println("OK " + out);
            System.exit(0);
        } catch (SegmentDownloader.SkipException e) {
            log.info("Пропущено / skipped: {}", e.getMessage());
            System.out.println("SKIPPED " + e.getMessage());
            System.exit(3);
        } catch (Exception e) {
            log.error("Ошибка загрузки / download error: {}", e.toString());
            System.out.println("ERROR " + e);
            System.exit(1);
        }
    }

    /** GUI-режим (TZ 5.2): сначала двухпанельное окно, выбор папки — диалогом поверх
     * него (отмена = выход 0). GUI mode (TZ 5.2): two-pane window first, folder picker
     * as a dialog over it (cancel = exit 0). */
    private static void runGui() throws Exception {
        Settings settings = Settings.load();

        LogPanel logPanel = new LogPanel(() -> Path.of(settings.downloadDir));
        SwingLogAppender.setSink(logPanel::appendLine);

        FfmpegRunner ffmpeg;
        try {
            ffmpeg = new FfmpegRunner();
        } catch (Exception e) {
            logPanel.appendLine("FFmpeg недоступен / FFmpeg unavailable: " + e.getMessage());
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

        SourceListPanel list = new SourceListPanel(registry, queue, launcher);
        MainWindow w = new MainWindow(list, logPanel, settings, queue);
        SwingUtilities.invokeLater(() -> w.setVisible(true));

        String[] sel = new String[1];
        int[] choice = new int[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setDialogTitle("Папка загрузок / Download folder");
                if (settings.downloadDir != null) {
                    File d = new File(settings.downloadDir);
                    if (d.isDirectory()) {
                        chooser.setCurrentDirectory(d);
                    }
                }
                choice[0] = chooser.showOpenDialog(w);
                if (choice[0] == JFileChooser.APPROVE_OPTION) {
                    sel[0] = chooser.getSelectedFile().getPath();
                }
            });
        } catch (Exception e) {
            choice[0] = JFileChooser.CANCEL_OPTION;
        }
        if (choice[0] != JFileChooser.APPROVE_OPTION) {
            w.dispose();
            System.exit(0);
            return;
        }
        settings.downloadDir = sel[0];
        settings.save();
        System.setProperty("hlsdir", settings.downloadDir);
        reconfigureLogging();
        logPanel.appendLine("ffmpeg: " + ffmpeg.version());
        logPanel.appendLine("Папка загрузок / download folder: " + settings.downloadDir);

        Thread launchThread = new Thread(() -> {
            try {
                logPanel.setStatusLine("Запускаю браузер… / launching the browser… (первый запуск может занять 10–20 с / first run may take 10–20 s)");
                logPanel.appendLine("Запускаю браузер (headful, persistent-профиль)… / launching the browser (headful, persistent profile)…");
                BrowserLauncher l = new BrowserLauncher(
                        Path.of(settings.downloadDir, ".profile"), settings.userAgent, false);
                launcher.set(l);
                queue.setLauncher(l);
                NetworkSniffer sniffer = new NetworkSniffer(registry);
                for (Page p : l.context().pages()) {
                    sniffer.attach(p);
                }
                // пользователь открывает сайт в новой вкладке → цепляем сниффер,
                // чтобы его строки появлялись
                // user opens a site in a new tab → attach the sniffer so its rows appear
                l.context().onPage(page -> {
                    sniffer.attach(page);
                    String u;
                    try {
                        u = page.url();
                    } catch (Exception e) {
                        u = "?";
                    }
                    logPanel.appendLine("Новая вкладка / new tab: " + u);
                });
                logPanel.appendLine("Браузер запущен / browser launched: " + l.channel());
                logPanel.setStatusLine("Браузер готов / browser ready (" + l.channel()
                        + ") — введите URL и нажмите «Открыть URL / Open URL»");
                SwingUtilities.invokeLater(() -> list.setOpenEnabled(true));
            } catch (Exception e) {
                logPanel.appendLine("Браузер не запущен / browser not launched: " + e);
                logPanel.setStatusLine("Браузер не запущен / browser not launched — проверьте Chrome/Edge и антивирус / check Chrome/Edge and your antivirus");
            }
        }, "browser-launch");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    /** Перезапуск конфига logback после System.setProperty("hlsdir", ...), чтобы
     * файловый аппендер попал в папку загрузок. Re-run logback config after
     * System.setProperty("hlsdir", ...) so file appender lands in downloads. */
    static void reconfigureLogging() {
        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.reset();
            JoranConfigurator j = new JoranConfigurator();
            j.setContext(ctx);
            j.doConfigure(App.class.getResourceAsStream("/logback.xml"));
        } catch (Exception e) {
            System.err.println("logback reconfigure failed: " + e);
        }
    }

    private static Path downloadDirect(HttpFetcher fetcher, String url, Path outDir, String name)
            throws Exception {
        byte[] body = fetcher.get(url);
        Path out = FileNames.build(outDir.toFile(), name, ".mp4").toPath();
        Files.write(out, body);
        return out;
    }

    private static String originOf(String referer) {
        if (referer == null) {
            return null;
        }
        try {
            java.net.URI u = java.net.URI.create(referer);
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(u.getScheme()).append("://").append(u.getHost());
            if (u.getPort() > 0 && u.getPort() != 80 && u.getPort() != 443) {
                sb.append(':').append(u.getPort());
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String defaultName(String url) {
        String path = UrlUtils.pathOf(url);
        String last = path.substring(path.lastIndexOf('/') + 1);
        String base = last.contains(".") ? last.substring(0, last.indexOf('.')) : last;
        return base.isEmpty() ? "video" : base;
    }

    record Options(String url, String out, String name, String referer, String ua, String cookie,
                   Integer parallel, boolean ffmpegSelftest, boolean gui) {

        static Options parse(String[] args) {
            String url = null, out = null, name = null, referer = null, ua = null, cookie = null;
            Integer parallel = null;
            boolean selftest = false;
            boolean gui = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--url" -> { url = next(args, i); i++; }
                    case "--out" -> { out = next(args, i); i++; }
                    case "--name" -> { name = next(args, i); i++; }
                    case "--referer" -> { referer = next(args, i); i++; }
                    case "--ua" -> { ua = next(args, i); i++; }
                    case "--cookie" -> { cookie = next(args, i); i++; }
                    case "--parallel" -> { parallel = Integer.parseInt(next(args, i)); i++; }
                    case "--ffmpeg-selftest" -> selftest = true;
                    case "--gui" -> gui = true;
                    default -> {
                        return null;
                    }
                }
            }
            if (gui) {
                return new Options(null, null, null, null, null, null, null, false, true);
            }
            if (args.length == 0) {
                // Двойной клик / запуск из IDE без аргументов: GUI — дефолт продукта.
                // Double-click / IDE run with no args: GUI is the product default.
                return new Options(null, null, null, null, null, null, null, false, true);
            }
            if (selftest) {
                return new Options(url, out, name, referer, ua, cookie, parallel, true, false);
            }
            if (url == null || out == null) {
                return null;
            }
            return new Options(url, out, name, referer, ua, cookie, parallel, false, false);
        }

        private static String next(String[] args, int i) {
            return i + 1 < args.length ? args[i + 1] : null;
        }
    }
}
