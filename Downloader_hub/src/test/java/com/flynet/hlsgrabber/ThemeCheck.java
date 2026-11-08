package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.ffmpeg.FfmpegRunner;
import com.flynet.hlsgrabber.manifest.SourceRecord;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import com.flynet.hlsgrabber.ui.LogPanel;
import com.flynet.hlsgrabber.ui.MainWindow;
import com.flynet.hlsgrabber.ui.SourceListPanel;
import com.flynet.hlsgrabber.ui.SwingLogAppender;
import com.flynet.hlsgrabber.ui.Theme;
import com.flynet.hlsgrabber.ui.ThemeManager;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Off-screen pixel check for the Apple Glass themes.
 *
 * Builds the real App GUI wiring (registry + queue + panels + window), injects
 * sample sources, shows the window (peer needed for layout), then paints the
 * layered pane into BufferedImages ON THE EDT. Key components are located in the
 * live tree (roles / texts / classes) and each painted region is scanned for its
 * theme color: header background + accent title + secondary folder label, toggle
 * pill fill, URL field fill, primary Open URL accent, card fill + border ring +
 * title / url / FOUND-status texts + row primary accent + quality combo fill,
 * log status accent + log field fill. Light, then toggle to dark, then save hook.
 *
 * Exit: 0 = all samples match, 1 = failures.
 */
public final class ThemeCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        p("PHASE: build");
        Settings settings = Settings.load();
        settings.minSizeBytes = 1_048_576L;
        settings.parallelSegments = 4;
        settings.targetHeight = 1080;
        settings.downloadDir = "D:/Java/Downloader/spikes/r1-r2/ui-out";

        ThemeManager.init("light");
        String[] saved = {""};
        ThemeManager.setSaveHook(name -> saved[0] = name);

        LogPanel logPanel = new LogPanel(() -> Path.of(settings.downloadDir));
        SwingLogAppender.setSink(logPanel::appendLine);
        FfmpegRunner ffmpeg = new FfmpegRunner();
        SourceRegistry registry = new SourceRegistry(settings.minSizeBytes);
        DownloadQueue queue = new DownloadQueue(registry, settings, ffmpeg);
        queue.setLauncher(null);
        queue.setIdleHook(() -> { });

        AtomicReference<BrowserLauncher> launcher = new AtomicReference<>();
        SourceListPanel list = new SourceListPanel(registry, queue, launcher);
        MainWindow w = new MainWindow(list, logPanel, settings, queue);
        ThemeManager.addWindow(w);
        w.setFolder(settings.downloadDir);
        // browser-ready state for the pixel check: Open URL paints its accent fill
        list.setOpenEnabled(true);

        // inject records, mimicking sniffer output
        registry.add("https://cdn.example/vid/master.m3u8?token=abc", SourceRecord.Kind.HLS,
                "https://site.example/watch?v=1", "Test Video Title", 0);
        registry.add("https://cdn.example/vid/file.mp4", SourceRecord.Kind.MP4,
                "https://site.example/watch?v=1", "Direct File", 8_000_000L);
        SourceRecord hlsRec = registry.records().stream()
                .filter(r -> r.canonicalUrl.startsWith("https://cdn.example/vid/master.m3u8"))
                .findFirst()
                .orElseThrow();
        hlsRec.setQualities(List.of(720, 1080, 480));

        logPanel.appendLine("ffmpeg: 6.0");
        logPanel.appendLine("download folder: " + settings.downloadDir);
        logPanel.setStatusLine("browser ready, enter the URL and click Open URL");

        w.setSize(1000, 680);
        p("PHASE: pre-visible");
        // show on the EDT: main-thread setVisible + AWTTreeLock races with the
        // logging appendLine on the EDT -> classic Swing deadlock (jstack-proven)
        SwingUtilities.invokeAndWait(() -> w.setVisible(true));
        p("PHASE: visible");
        // let the Swing timer (700 ms) build rows; peer layout completes on the EDT
        p("PHASE: sleep 1800 begin");
        Thread.sleep(1800);
        p("PHASE: sleep done");
        p("PHASE: validate begin");
        SwingUtilities.invokeAndWait(w::validate);
        p("PHASE: validate done");

        p("PHASE: check light");
        check(w, ThemeManager.current(), "light");
        p("PHASE: toggle");
        SwingUtilities.invokeAndWait(ThemeManager::toggle);
        p("PHASE: toggle done");
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(w::validate);
        p("PHASE: check dark");
        check(w, ThemeManager.current(), "dark");

        System.out.println("HOOK_SAVE=" + saved[0] + " (expected dark)");
        if (!"dark".equals(saved[0])) {
            failures++;
        }

        w.dispose();
        if (failures == 0) {
            System.out.println("THEME_CHECK: PASS");
        } else {
            System.out.println("THEME_CHECK: FAIL (" + failures + ")");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Paint the layered pane on the EDT, then scan the key components' regions. */
    private static void check(MainWindow w, Theme t, String label) {
        BufferedImage img = new BufferedImage(1000, 680, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        p("[" + label + "] paint begin");
        try {
            SwingUtilities.invokeAndWait(() -> w.getLayeredPane().paint(g));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        p("[" + label + "] paint done");
        g.dispose();

        JLayeredPane lp = w.getLayeredPane();
        List<Component> all = new ArrayList<>();
        collect(w.getRootPane(), all);
        JPanel header = (JPanel) w.getRootPane().getContentPane().getComponents()[0];

        JLabel titleAccent = findRoleLabel(all, "titleAccent", "HLS Grabber");
        JLabel folderLabel = findRoleLabel(all, "secondary", "Folder:");
        JButton toggle = findButton(all, "Theme:");
        JTextField urlField = (JTextField) findFirst(all, JTextField.class);
        JButton openUrl = findButton(all, "Open URL");
        JLabel rowUrlLabel = (JLabel) findRoleLabel(all, "secondary", "https://cdn.example/vid/master.m3u8");
        JComponent row = (JComponent) rowUrlLabel.getParent();
        JLabel statusLabel = findRoleLabel(all, "accent", null);
        JTextArea logArea = (JTextArea) findFirst(all, JTextArea.class);

        scanAll("[" + label + "] header bg = panel color", img, lp, header, t.panelBackground(), 8);
        scan("[" + label + "] title accent text", img, lp, titleAccent, t.accent(), 24);
        scan("[" + label + "] header folder label secondary", img, lp, folderLabel, t.textSecondary(), 24);
        scan("[" + label + "] toggle pill fill", img, lp, toggle, t.buttonFill(), 10);
        scan("[" + label + "] url field fill", img, lp, urlField, t.fieldBackground(), 6);
        scan("[" + label + "] primary Open URL button accent", img, lp, openUrl, t.accent(), 10);

        List<Component> rowParts = new ArrayList<>();
        collect(row, rowParts);
        JLabel rowTitle = findRoleLabel(rowParts, "title", null);
        JLabel rowUrl = findRoleLabel(rowParts, "secondary", "https://cdn.example/vid/master.m3u8");
        JLabel rowStatus = findStatus(rowParts);
        JButton rowButton = findButton(rowParts, "Download");
        JComboBox<?> rowCombo = (JComboBox<?>) findFirst(rowParts, JComboBox.class);

        scan("[" + label + "] card fill", img, lp, row, t.cardBackground(), 6);
        scan("[" + label + "] row border ring", img, lp, row, t.cardBorder(), 14);
        scan("[" + label + "] row title text", img, lp, rowTitle, t.textPrimary(), 24);
        scan("[" + label + "] row url secondary text", img, lp, rowUrl, t.textSecondary(), 24);
        scan("[" + label + "] row FOUND status accent", img, lp, rowStatus, t.accent(), 24);
        scan("[" + label + "] row primary button accent", img, lp, rowButton, t.accent(), 10);
        scan("[" + label + "] quality combo fill", img, lp, rowCombo, t.fieldBackground(), 6);

        scan("[" + label + "] status label accent", img, lp, statusLabel, t.accent(), 24);
        scan("[" + label + "] log field fill", img, lp, logArea, t.fieldBackground(), 6);
    }

    /** Print a phase marker, flushed immediately, to stream diagnostics during runs. */
    private static void p(String s) {
        System.out.println(s);
        System.out.flush();
    }

    /** Scan every painted pixel inside the component bounds for the theme color. */
    private static void scan(String what, BufferedImage img, JLayeredPane lp, Component c, Color col, int tol) {
        Rectangle r = map(c, lp);
        boolean found = false;
        int x1 = Math.min(img.getWidth(), r.x + r.width);
        int y1 = Math.min(img.getHeight(), r.y + r.height);
        outer:
        for (int yy = Math.max(0, r.y); yy < y1; yy++) {
            for (int xx = Math.max(0, r.x); xx < x1; xx++) {
                if (close(img.getRGB(xx, yy), col, tol)) {
                    found = true;
                    break outer;
                }
            }
        }
        System.out.println((found ? "OK   " : "FAIL ") + what + " bounds=" + r);
        if (!found) {
            failures++;
        }
    }

    /** Every pixel inside the component bounds must be within tolerance. */
    private static void scanAll(String what, BufferedImage img, JLayeredPane lp, Component c, Color col, int tol) {
        Rectangle r = map(c, lp);
        int x1 = Math.min(img.getWidth(), r.x + r.width);
        int y1 = Math.min(img.getHeight(), r.y + r.height);
        int bad = 0;
        int total = 0;
        for (int yy = Math.max(0, r.y); yy < y1; yy++) {
            for (int xx = Math.max(0, r.x); xx < x1; xx++) {
                total++;
                if (!close(img.getRGB(xx, yy), col, tol)) {
                    bad++;
                }
            }
        }
        // header area includes labels/buttons; allow small minority of non-bg pixels
        int allowance = total / 3;
        boolean ok = total > 0 && bad <= allowance;
        System.out.println((ok ? "OK   " : "FAIL ") + what + " bad=" + bad + "/" + total);
        if (!ok) {
            failures++;
        }
    }

    private static Rectangle map(Component c, JLayeredPane lp) {
        int x = 0;
        int y = 0;
        Component p = c;
        while (p != null && p != lp) {
            x += p.getX();
            y += p.getY();
            p = p.getParent();
        }
        return new Rectangle(x, y, c.getWidth(), c.getHeight());
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof JComponent jc) {
            for (Component child : jc.getComponents()) {
                collect(child, out);
            }
        }
    }

    private static JLabel findRoleLabel(List<Component> all, String role, String textPrefix) {
        for (Component c : all) {
            if (c instanceof JLabel l && role.equals(l.getClientProperty("role"))) {
                String t = l.getText();
                if (textPrefix == null || (t != null && t.startsWith(textPrefix))) {
                    return l;
                }
            }
        }
        return null;
    }

    private static JButton findButton(List<Component> all, String textPrefix) {
        for (Component c : all) {
            if (c instanceof JButton b && b.getText().startsWith(textPrefix)) {
                return b;
            }
        }
        return null;
    }

    private static JLabel findStatus(List<Component> rowParts) {
        for (Component c : rowParts) {
            if (c instanceof JLabel l && l.getText().startsWith("Found")) {
                return l;
            }
        }
        return null;
    }

    private static Component findFirst(List<Component> all, Class<?> cls) {
        for (Component c : all) {
            if (cls.isInstance(c)) {
                return c;
            }
        }
        return null;
    }

    private static boolean close(int rgb, Color c, int tol) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return Math.abs(r - c.getRed()) <= tol
                && Math.abs(g - c.getGreen()) <= tol
                && Math.abs(b - c.getBlue()) <= tol;
    }
}
