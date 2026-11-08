package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.manifest.SourceRecord;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.flynet.hlsgrabber.ui.fx.SourceListFx;
import com.flynet.hlsgrabber.ui.fx.SourceRowFx;
import com.flynet.hlsgrabber.ui.fx.ThemeBridge;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UI harness (FX mirror of Swing GuiUiCheck): fake records, snapshot-based pixel
 * checks of card fill/border, text labels, button/combo fills, enqueue via the
 * row button. Shows briefly on screen (FX toolkit needs a display); exits on completion.
 */
public class GuiUiCheckFx {

    static final String OUT_DIR = "D:/Java/Downloader/spikes/r1-r2/ui-out";
    static final List<String> fails = new ArrayList<>();
    static final StringBuilder out = new StringBuilder();

    static PixelReader r;
    static int iw, ih;

    public static void main(String[] args) {
        out.append("FX_UI_CHECK\n");
        Application.launch(HarnessApp.class, args);
    }

    public static class HarnessApp extends Application {
        @Override
        public void start(Stage stage) throws IOException {
        new File(OUT_DIR).mkdirs();
        Settings settings = new Settings();
        settings.downloadDir = OUT_DIR;
        settings.minSizeBytes = 1_048_576L;
        settings.parallelSegments = 4;
        settings.targetHeight = 1080;
        SourceRegistry registry = new SourceRegistry(settings.minSizeBytes);
        DownloadQueue queue = new DownloadQueue(registry, settings, new FfmpegRunner());
        queue.setLauncher(null);
        queue.setIdleHook(() -> { });
        SourceListFx list = new SourceListFx(registry, queue, new AtomicReference<BrowserLauncher>());

        BorderPane root = new BorderPane();
        root.setCenter(list);
        Scene scene = new Scene(root, 900, 520);
        stage.setTitle("GuiUiCheckFx");
        stage.setScene(scene);
        stage.show();

        SourceRecord hls = new SourceRecord(
                "https://example.com/master.m3u8?token=abc",
                SourceRecord.Kind.HLS, "https://example.com/watch?v=1", "Test Video Title", 0);
        registry.add(hls);
        hls.setQualities(List.of(720, 1080, 480));
        registry.add(new SourceRecord("https://cdn.example.com/file.mp4",
                SourceRecord.Kind.MP4, "https://example.com/watch?v=2", "MP4 Direct File", 8_000_000L));
        registry.add(new SourceRecord("https://cdn.example.com/small.mp4",
                SourceRecord.Kind.MP4, "https://example.com/watch?v=3", "Too Small", 500_000L));
        registry.add(new SourceRecord("https://cdn.example.com/seg-1-v1-a1.m4s?e=179",
                SourceRecord.Kind.MP4, "https://example.com/watch?v=4", "M4S Segment", 3_000_000L));

        Timeline t = new Timeline(new KeyFrame(Duration.millis(1500), e -> {
            snapshot(scene);
            runChecks(list, queue, hls);
            if (hlsRow != null) {
                check(!queue.hasActiveTasks(), "queue not idle before fire");
                hlsRow.actionButton().fire();
                AtomicInteger waited = new AtomicInteger();
                Timeline poll = new Timeline(new KeyFrame(Duration.millis(400), e2 -> {
                    SourceRecord.State st = hls.state();
                    if (st != SourceRecord.State.FOUND) {
                        out.append("hlsStateAfterClick=" + st + "\n");
                        out.append("BUTTON_ENQUEUE=" + hlsRow.actionButton().getText() + "\n");
                        check(st == SourceRecord.State.QUEUED
                                        || st == SourceRecord.State.DOWNLOADING
                                        || st == SourceRecord.State.ERROR,
                                "state after click unexpected: " + st);
                        check(hlsRow.actionButton().getText().equals("Cancel"),
                                "button after enqueue not Cancel: " + hlsRow.actionButton().getText());
                        check(queue.hasActiveTasks(), "queue not active after enqueue");
                        saveSnapshot();
                        finish();
                    } else if (waited.addAndGet(400) >= 10_000) {
                        out.append("hlsStateAfterClick=" + hls.state() + " (timeout)\n");
                        check(false, "state stayed FOUND for 10s");
                        saveSnapshot();
                        finish();
                    }
                }));
                poll.setCycleCount(Animation.INDEFINITE);
                poll.play();
            } else {
                saveSnapshot();
                finish();
            }
        }));
        t.play();
    }
    }

    static SourceRowFx hlsRow, mp4Row;

    static void snapshot(Scene scene) {
        WritableImage img = scene.getRoot().snapshot(new SnapshotParameters(), null);
        iw = (int) img.getWidth();
        ih = (int) img.getHeight();
        r = img.getPixelReader();
    }

    static void runChecks(SourceListFx list, DownloadQueue queue, SourceRecord hls) {
        List<SourceRowFx> rows = new ArrayList<>();
        for (Node n : list.listArea().getChildren()) {
            if (n instanceof SourceRowFx row) {
                rows.add(row);
            }
        }
        int rowCount = rows.size();
        out.append("rows=" + rowCount + "\n");
        check(rowCount == 3, "rows expected 3 (small.mp4 filtered), got " + rowCount);

        for (SourceRowFx row : rows) {
            if (row.record() == hls) hlsRow = row;
            if (row.record().canonicalUrl.contains("file.mp4")) mp4Row = row;
        }

        check(hlsRow != null && hlsRow.urlLabel().getText().contains("master.m3u8"),
                "hls url label missing url");
        check(hlsRow != null && hlsRow.titleLabel().getText().contains("Test Video Title"),
                "hls title label missing title");
        check(hlsRow != null && hlsRow.infoLabel().getText().contains("Found · qualities: 1080p, 720p, 480p"),
                "qualities not shown: " + (hlsRow == null ? "row missing" : hlsRow.infoLabel().getText()));

        int light = 0x1C1C1E, sec = 0x6E7075, accent = 0x007AFF, white = 0xFFFFFF;
        int[] br = ThemeBridge.rgb(ThemeBridge.LIGHT.cardBorder());
        int border = (br[0] << 16) | (br[1] << 8) | br[2];
        if (hlsRow != null) {
            int[] b = boundsInScene(hlsRow);
            double fill = coverage(b, white, 6, 4);
            out.append("CARD_FILL=" + String.format("%.3f", fill) + "\n");
            check(fill >= 0.5, "card fill not white " + fill);
            int ring = ringCount(b, border, 14);
            out.append("CARD_BORDER=" + ring + "\n");
            check(ring >= 2, "card border ring not cardBorder " + ring);
            int[] tb = boundsInScene(hlsRow.titleLabel());
            int tc = count(tb, light, 24);
            out.append("TITLE_TEXT=" + tc + "\n");
            check(tc >= 8, "title text pixels missing");
            int[] ub = boundsInScene(hlsRow.urlLabel());
            int uc = count(ub, sec, 24);
            out.append("URL_TEXT=" + uc + "\n");
            check(uc >= 6, "url text pixels missing");
            int[] ib = boundsInScene(hlsRow.infoLabel());
            int ic = count(ib, accent, 24);
            out.append("FOUND_ACCENT=" + ic + "\n");
            check(ic >= 6, "found accent pixels missing");
            int[] cb = boundsInScene(hlsRow.qualitySelector());
            double cf = coverage(cb, white, 6, 3);
            out.append("COMBO_FILL=" + String.format("%.3f", cf) + "\n");
            check(cf >= 0.4, "combo fill not white " + cf);
            out.append("DOWNLOAD_BUTTON=" + hlsRow.actionButton().getText() + "\n");
            check(hlsRow.actionButton().getText().startsWith("Download"),
                    "hls button text unexpected: " + hlsRow.actionButton().getText());
        }
        check(mp4Row != null, "mp4 row missing");
        if (mp4Row != null) {
            int[] bb = boundsInScene(mp4Row.actionButton());
            double bc = coverage(bb, accent, 10, 3);
            out.append("ROW_BUTTON_FILL=" + String.format("%.3f", bc) + "\n");
            check(bc >= 0.35, "row button not accent-filled " + bc);
        }
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

    /** coverage of target color over the rect inset by margin px on all sides */
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

    /** mid-edge ring pixels matching target */
    static int ringCount(int[] b, int target, int tol) {
        int x = b[0], y = b[1], w = b[2], h = b[3];
        int[][] pts = { {x + w / 2, y}, {x + w / 2, y + h - 1}, {x, y + h / 2}, {x + w - 1, y + h / 2} };
        int n = 0;
        for (int[] p : pts) {
            if (p[0] >= 0 && p[1] >= 0 && p[0] < iw && p[1] < ih
                    && close(r.getArgb(p[0], p[1]), target, tol)) {
                n++;
            }
        }
        return n;
    }

    static void saveSnapshot() {
        try {
            BufferedImage bi = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    bi.setRGB(x, y, r.getArgb(x, y));
                }
            }
            File f = new File(OUT_DIR + "/fx-harness.png");
            ImageIO.write(bi, "png", f);
            out.append("SNAPSHOT=" + f.getPath() + "\n");
        } catch (IOException e) {
            out.append("SNAPSHOT_FAILED=" + e + "\n");
            check(false, "snapshot save failed");
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) {
            fails.add(msg);
            out.append("FAIL: " + msg + "\n");
        }
    }

    static void finish() {
        out.append(fails.isEmpty() ? "UI_CHECK: PASS\n" : "UI_CHECK: FAIL (" + fails.size() + ")\n");
        System.out.println(out);
        System.exit(fails.isEmpty() ? 0 : 1);
    }
}
