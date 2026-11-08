package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.manifest.SourceRecord;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.flynet.hlsgrabber.ui.fx.GuiLogAppender;
import com.flynet.hlsgrabber.ui.fx.LogFx;
import com.flynet.hlsgrabber.ui.fx.SourceListFx;
import com.flynet.hlsgrabber.ui.fx.SourceRowFx;
import com.flynet.hlsgrabber.ui.fx.ThemeBridge;
import com.flynet.hlsgrabber.ui.fx.FxMainWindow;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Theme harness (FX mirror of Swing ThemeCheck): verifies the "smart" dark theme.
 * Light scan by Cupertino light tokens, then live toggle to dark and a second scan
 * by Cupertino dark tokens — every check reads ThemeBridge palette tokens, so the
 * harness tracks the palette, not hardcoded hexes. Shows briefly on screen.
 */
public class ThemeCheckFx {

    static final String OUT_DIR = "D:/Java/Downloader/spikes/r1-r2/ui-out";
    static final List<String> fails = new ArrayList<>();
    static final StringBuilder out = new StringBuilder();

    static PixelReader r;
    static int iw, ih;
    static final List<String> hookSaves = new ArrayList<>();

    public static void main(String[] args) {
        out.append("THEME_CHECK\n");
        Application.launch(HarnessApp.class, args);
    }

    public static class HarnessApp extends Application {
        @Override
        public void start(Stage stage) throws IOException {
        new File(OUT_DIR).mkdirs();
        ThemeBridge.init("light");
        ThemeBridge.setSaveHook(s -> hookSaves.add(s));

        Settings settings = new Settings();
        settings.downloadDir = OUT_DIR;
        settings.minSizeBytes = 1_048_576L;
        settings.parallelSegments = 4;
        settings.targetHeight = 1080;
        SourceRegistry registry = new SourceRegistry(settings.minSizeBytes);
        DownloadQueue queue = new DownloadQueue(registry, settings, new FfmpegRunner());
        queue.setLauncher(null);
        queue.setIdleHook(() -> { });

        LogFx log = new LogFx(() -> Path.of(OUT_DIR));
        GuiLogAppender.setSink(log::appendLine);
        log.appendLine("ffmpeg: 6.0");

        SourceListFx list = new SourceListFx(registry, queue, new AtomicReference<BrowserLauncher>());
        list.setOpenEnabled(true);
        FxMainWindow win = new FxMainWindow(stage, list, log, settings, queue);
        win.setFolder("D:\\downloads");

        SourceRecord hls = new SourceRecord(
                "https://example.com/master.m3u8?token=abc",
                SourceRecord.Kind.HLS, "https://example.com/watch?v=1", "Test Video Title", 0);
        hls.setQualities(List.of(720, 1080, 480));
        registry.add(hls);
        registry.add(new SourceRecord("https://cdn.example.com/file.mp4",
                SourceRecord.Kind.MP4, "https://example.com/watch?v=2", "MP4 Direct File", 8_000_000L));

        Scene scene = win.scene();
        win.show();

        Timeline t = new Timeline(new KeyFrame(Duration.millis(1800), e -> {
            snapshot(scene);
            savePng("theme-light.png");
            runChecks(list, log, win, true);
            win.themeToggle().fire();
            out.append("toggleText=" + win.themeToggle().getText() + "\n");
            check(win.themeToggle().getText().equals("Theme: Dark"), "toggle text not Dark");
            check(ThemeBridge.mode() == ThemeBridge.Mode.DARK, "mode not DARK after toggle");
            check(hookSaves.contains("dark"), "save hook did not record dark");
            Timeline t2 = new Timeline(new KeyFrame(Duration.millis(1200), e2 -> {
                snapshot(scene);
                savePng("theme-dark.png");
                runChecks(list, log, win, false);
                finish();
            }));
            t2.play();
        }));
        t.play();
    }
    }

    static void snapshot(Scene scene) {
        WritableImage img = scene.getRoot().snapshot(new SnapshotParameters(), null);
        iw = (int) img.getWidth();
        ih = (int) img.getHeight();
        r = img.getPixelReader();
    }

    /** Finds header labels: first Label=title, second Label=folder in the top HBox. */
    static Label findHeaderLabel(Scene scene, int index) {
        BorderPane root = (BorderPane) scene.getRoot();
        HBox header = (HBox) root.getTop();
        int seen = 0;
        for (Node n : header.getChildren()) {
            if (n instanceof Label l) {
                if (seen == index) {
                    return l;
                }
                seen++;
            }
        }
        return null;
    }

    static void runChecks(SourceListFx list, LogFx log, FxMainWindow win, boolean light) {
        Scene scene = win.scene();
        ThemeBridge.Palette p = ThemeBridge.palette(light ? ThemeBridge.Mode.LIGHT : ThemeBridge.Mode.DARK);
        int panel = rgb(p.panel()), card = rgb(p.card()), border = rgb(p.cardBorder());
        int field = rgb(p.field()), textP = rgb(p.textPrimary()), textS = rgb(p.textSecondary());
        int accent = rgb(p.accent());
        String tag = light ? "L" : "D";

        BorderPane root = (BorderPane) scene.getRoot();
        HBox header = (HBox) root.getTop();

        Label title = findHeaderLabel(scene, 0);
        Label folder = findHeaderLabel(scene, 1);
        check(title != null && folder != null, tag + ": header labels missing");

        double hcov = coverage(boundsInScene(header), panel, 8, 3);
        out.append(tag + "_HEADER_PANEL=" + fmt(hcov) + "\n");
        check(hcov >= 0.8, tag + ": header panel coverage " + hcov);

        if (title != null) {
            int tc = count(boundsInScene(title), accent, 24);
            out.append(tag + "_TITLE_ACCENT=" + tc + "\n");
            check(tc >= 6, tag + ": title accent pixels " + tc);
        }
        if (folder != null) {
            int fc = count(boundsInScene(folder), textS, 24);
            out.append(tag + "_FOLDER_SECONDARY=" + fc + "\n");
            check(fc >= 4, tag + ": folder secondary pixels " + fc);
        }

        double tgl = coverage(boundsInScene(win.themeToggle()), accent, 10, 3);
        out.append(tag + "_TOGGLE_FILL=" + fmt(tgl) + "\n");
        check(tgl >= 0.35, tag + ": toggle not accent-filled " + tgl);

        double uf = coverage(boundsInScene(list.urlField()), field, 6, 3);
        out.append(tag + "_URLFIELD_FILL=" + fmt(uf) + "\n");
        check(uf >= 0.4, tag + ": url field fill " + uf);

        double ob = coverage(boundsInScene(list.openUrlButton()), accent, 10, 3);
        out.append(tag + "_OPENURL_FILL=" + fmt(ob) + "\n");
        check(ob >= 0.35, tag + ": open url button not accent-filled " + ob);

        SourceRowFx hlsRow = null, mp4Row = null;
        for (Node n : list.listArea().getChildren()) {
            if (n instanceof SourceRowFx row) {
                if (row.record().canonicalUrl.contains("master.m3u8")) hlsRow = row;
                if (row.record().canonicalUrl.contains("file.mp4")) mp4Row = row;
            }
        }
        check(hlsRow != null && mp4Row != null, tag + ": rows missing");

        if (hlsRow != null) {
            double cf = coverage(boundsInScene(hlsRow), card, 6, 4);
            out.append(tag + "_CARD_FILL=" + fmt(cf) + "\n");
            check(cf >= 0.5, tag + ": card fill " + cf);

            int ring = ringCount(boundsInScene(hlsRow), border, 14);
            out.append(tag + "_CARD_BORDER=" + ring + "\n");
            check(ring >= 2, tag + ": card border ring " + ring);

            int rtc = count(boundsInScene(hlsRow.titleLabel()), textP, 24);
            out.append(tag + "_ROW_TITLE=" + rtc + "\n");
            check(rtc >= 8, tag + ": row title text pixels " + rtc);

            int ruc = count(boundsInScene(hlsRow.urlLabel()), textS, 24);
            out.append(tag + "_ROW_URL=" + ruc + "\n");
            check(ruc >= 6, tag + ": row url text pixels " + ruc);

            int facc = count(boundsInScene(hlsRow.infoLabel()), accent, 24);
            out.append(tag + "_FOUND_ACCENT=" + facc + "\n");
            check(facc >= 6, tag + ": found accent pixels " + facc);

            double cbf = coverage(boundsInScene(hlsRow.qualitySelector()), field, 6, 3);
            out.append(tag + "_COMBO_FILL=" + fmt(cbf) + "\n");
            check(cbf >= 0.4, tag + ": combo fill " + cbf);
        }
        if (mp4Row != null) {
            double rb = coverage(boundsInScene(mp4Row.actionButton()), accent, 10, 3);
            out.append(tag + "_ROW_BUTTON=" + fmt(rb) + "\n");
            check(rb >= 0.35, tag + ": row button not accent-filled " + rb);
        }

        int sl = count(boundsInScene(log.statusLabelNode()), accent, 24);
        out.append(tag + "_STATUS_ACCENT=" + sl + "\n");
        check(sl >= 4, tag + ": status label accent pixels " + sl);

        double lf = coverage(boundsInScene(log.logArea()), field, 6, 3);
        out.append(tag + "_LOG_FIELD=" + fmt(lf) + "\n");
        check(lf >= 0.5, tag + ": log field fill " + lf);

        int lt = count(boundsInScene(log.logArea()), textP, 24);
        out.append(tag + "_LOG_TEXT=" + lt + "\n");
        check(lt >= 6, tag + ": log text pixels " + lt);

        int ok = 0;
        int[][] corners = { {0, 0}, {iw - 1, 0}, {0, ih - 1}, {iw - 1, ih - 1} };
        for (int[] c : corners) {
            int argb = r.getArgb(c[0], c[1]);
            int rr = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, bb = argb & 0xFF;
            boolean match = light ? (rr >= 244 && gg >= 244 && bb >= 244)
                    : (rr <= 34 && gg <= 34 && bb <= 34);
            if (match) ok++;
        }
        out.append(tag + "_CORNERS=" + ok + "\n");
        check(ok >= 3, tag + ": corner luminance mismatch " + ok);
    }

    static int rgb(String hex) {
        int[] v = ThemeBridge.rgb(hex);
        return (v[0] << 16) | (v[1] << 8) | v[2];
    }

    static String fmt(double d) {
        return String.format("%.3f", d);
    }

    static int[] boundsInScene(Node n) {
        Point2D p = n.localToScene(0, 0);
        int x = (int) Math.round(p.getX());
        int y = (int) Math.round(p.getY());
        int w = (int) Math.ceil(n.getLayoutBounds().getWidth());
        int h = (int) Math.ceil(n.getLayoutBounds().getHeight());
        return new int[]{x, y, w, h};
    }

    static boolean close(int argb, int target, int tol) {
        int rr = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, bb = argb & 0xFF;
        int tr = (target >> 16) & 0xFF, tg = (target >> 8) & 0xFF, tb = target & 0xFF;
        return Math.abs(rr - tr) <= tol && Math.abs(gg - tg) <= tol && Math.abs(bb - tb) <= tol;
    }

    static int count(int[] b, int target, int tol) {
        int n = 0;
        int x = Math.max(0, b[0]), y = Math.max(0, b[1]);
        int w = Math.min(b[2], iw - x), h = Math.min(b[3], ih - y);
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (close(r.getArgb(x + i, y + j), target, tol)) n++;
            }
        }
        return n;
    }

    static double coverage(int[] b, int target, int tol, int inset) {
        int x = Math.max(0, b[0] + inset), y = Math.max(0, b[1] + inset);
        int w = Math.max(1, Math.min(b[2] - 2 * inset, iw - x));
        int h = Math.max(1, Math.min(b[3] - 2 * inset, ih - y));
        int n = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (close(r.getArgb(x + i, y + j), target, tol)) n++;
            }
        }
        return (double) n / (w * h);
    }

    static int ringCount(int[] b, int target, int tol) {
        int x = b[0], y = b[1], w = b[2], h = b[3];
        int[][] pts = { {x + w / 2, y}, {x + w / 2, y + h - 1}, {x, y + h / 2}, {x + w - 1, y + h / 2} };
        int n = 0;
        for (int[] pt : pts) {
            if (pt[0] >= 0 && pt[1] >= 0 && pt[0] < iw && pt[1] < ih
                    && close(r.getArgb(pt[0], pt[1]), target, tol)) {
                n++;
            }
        }
        return n;
    }

    static void savePng(String name) {
        try {
            BufferedImage bi = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    bi.setRGB(x, y, r.getArgb(x, y));
                }
            }
            File f = new File(OUT_DIR + "/" + name);
            ImageIO.write(bi, "png", f);
            out.append("SNAPSHOT=" + f.getPath() + "\n");
        } catch (IOException e) {
            out.append("SNAPSHOT_FAILED=" + e + "\n");
            check(false, "snapshot save failed: " + name);
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) {
            fails.add(msg);
            out.append("FAIL: " + msg + "\n");
        }
    }

    static void finish() {
        out.append(fails.isEmpty() ? "THEME_CHECK: PASS\n" : "THEME_CHECK: FAIL (" + fails.size() + ")\n");
        System.out.println(out);
        System.exit(fails.isEmpty() ? 0 : 1);
    }
}
