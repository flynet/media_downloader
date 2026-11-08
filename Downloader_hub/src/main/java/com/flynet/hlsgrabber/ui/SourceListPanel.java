package com.flynet.hlsgrabber.ui;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.manifest.SourceRecord;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Upper GUI section (TZ 5.2): URL input + "Open URL" (browser navigation off the EDT)
 * and the rows panel (BoxLayout, not JTable). A ~700 ms timer repaints snapshots from
 * the registry; buttons dispatch actions to the queue.
 *
 * Central panel of the window: URL field, quality list, source
 * list (downloaded), buttons, log. Plus a clipboard monitor
 * (auto-detect links, TZ section 12).
 */
public final class SourceListPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(SourceListPanel.class);

    private final SourceRegistry registry;
    private final DownloadQueue queue;
    private final AtomicReference<BrowserLauncher> launcher;
    private final Map<String, SourceRowPanel> rows = new LinkedHashMap<>();
    private final JPanel listArea = new JPanel();
    private final JTextField urlField = new JTextField();
    private final JButton openUrlBtn = new JButton("Open URL");

    public SourceListPanel(SourceRegistry registry, DownloadQueue queue,
                           AtomicReference<BrowserLauncher> launcher) {
        this.registry = registry;
        this.queue = queue;
        this.launcher = launcher;

        setLayout(new BorderLayout(0, 6));

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.putClientProperty("role", "panel");
        top.setBorder(BorderFactory.createEmptyBorder(6, 12, 4, 12));
        openUrlBtn.putClientProperty("primary", true);
        openUrlBtn.setEnabled(false);
        openUrlBtn.setToolTipText("browser not ready yet");
        openUrlBtn.addActionListener(e -> {
            String u = urlField.getText().trim();
            if (u.isEmpty()) {
                return;
            }
            new Thread(() -> {
                BrowserLauncher l = launcher.get();
                if (l == null) {
                    log.info("browser not ready — URL not opened: {}", u);
                    return;
                }
                try {
                    l.openPage(u);
                } catch (Exception ex) {
                    log.warn("failed to open {}: {}", u, ex.toString());
                }
            }, "open-url").start();
        });
        top.add(urlField, BorderLayout.CENTER);
        top.add(openUrlBtn, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        listArea.setLayout(new BoxLayout(listArea, BoxLayout.Y_AXIS));
        listArea.putClientProperty("role", "panel");
        listArea.add(Box.createGlue());
        JScrollPane scroll = new JScrollPane(listArea);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        add(scroll, BorderLayout.CENTER);

        SourceRowPanel.RowActions actions = new SourceRowPanel.RowActions() {
            @Override
            public void enqueue(SourceRecord rec, int qualityHeight) {
                queue.enqueue(rec, qualityHeight);
            }

            @Override
            public void cancel(SourceRecord rec) {
                queue.cancel(rec);
            }

            @Override
            public void retry(SourceRecord rec) {
                queue.retry(rec);
            }

            @Override
            public void openOutput(String outPath) {
                if (outPath == null || outPath.isBlank()) {
                    return;
                }
                Path parent = Path.of(outPath).getParent();
                if (parent != null) {
                    openFolder(parent);
                }
            }
        };

        Timer timer = new Timer(700, e -> refresh(actions));
        timer.setRepeats(true);
        timer.start();
    }

    /** Enables/disables the "Open URL" button (browser readiness). EDT-safe. */
    public void setOpenEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            openUrlBtn.setEnabled(enabled);
            openUrlBtn.setToolTipText(enabled ? "open URL in the browser"
                    : "browser not ready yet");
        });
    }

    /** Runs on the EDT (Swing Timer). */
    private void refresh(SourceRowPanel.RowActions actions) {
        boolean added = false;
        for (SourceRecord r : registry.records()) {
            SourceRowPanel row = rows.get(r.canonicalUrl);
            if (row == null) {
                row = new SourceRowPanel(r, actions);
                ThemeManager.retheme(row);
                rows.put(r.canonicalUrl, row);
                listArea.add(row, listArea.getComponentCount() - 1);
                added = true;
            }
            row.update(r.snapshot());
        }
        if (added) {
            listArea.revalidate();
            listArea.repaint();
        }
    }

    private static void openFolder(Path dir) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir.toFile());
                }
            } catch (Exception e) {
                log.debug("open folder: {}", e.getMessage());
            }
        }, "open-folder").start();
    }
}
