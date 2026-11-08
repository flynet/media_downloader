package com.flynet.hlsgrabber.ui;

import com.flynet.hlsgrabber.manifest.SourceRecord;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * One source row as a rounded card (TZ 5.2): title, clipped URL (full in tooltip),
 * state line with progress/speed/stage, and a state-dependent primary action button.
 * Flat "Apple Glass" card: rounded fill, 1px border (accent on hover), soft shadow
 * layers; all colors follow the active theme at paint/update time.
 * Right-click: "Copy link".
 */
public final class SourceRowPanel extends JPanel {

    public interface RowActions {
        void enqueue(SourceRecord rec, int qualityHeight);
        void cancel(SourceRecord rec);
        void retry(SourceRecord rec);
        void openOutput(String outPath);
    }

    private record QualityOption(int height, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final int URL_CLIP = 100;

    private final SourceRecord rec;
    private final RowActions actions;
    private final JLabel titleLabel = new JLabel();
    private final JLabel urlLabel = new JLabel();
    private final JLabel infoLabel = new JLabel();
    private final JButton button = new JButton("Download");
    private final JComboBox<QualityOption> qualitySelector = new JComboBox<>();
    private List<QualityOption> displayedQualities = List.of();
    private boolean hover;

    public SourceRowPanel(SourceRecord rec, RowActions actions) {
        this.rec = rec;
        this.actions = actions;

        setLayout(new BorderLayout(8, 2));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 14, 10, 14));
        setAlignmentX(LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));

        titleLabel.putClientProperty("role", "title");
        urlLabel.putClientProperty("role", "secondary");
        ThemeManager.applyRoleStyles(titleLabel);
        ThemeManager.applyRoleStyles(urlLabel);

        add(titleLabel, BorderLayout.NORTH);
        add(urlLabel, BorderLayout.CENTER);
        add(infoLabel, BorderLayout.SOUTH);

        JPanel east = new JPanel();
        east.setOpaque(false);
        east.setLayout(new BoxLayout(east, BoxLayout.Y_AXIS));
        qualitySelector.setPreferredSize(new Dimension(140, 34));
        qualitySelector.setMaximumSize(new Dimension(140, 34));
        qualitySelector.setAlignmentX(Component.LEFT_ALIGNMENT);
        qualitySelector.addActionListener(e -> updateButtonText(rec.state()));
        east.add(qualitySelector);
        east.add(Box.createVerticalStrut(10));
        button.putClientProperty("primary", true);
        button.setMaximumSize(new Dimension(160, 34));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> onButton());
        east.add(button);
        add(east, BorderLayout.EAST);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Copy link");
        copy.addActionListener(e -> {
            StringSelection sel = new StringSelection(rec.canonicalUrl);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });
        popup.add(copy);
        MouseAdapter popupTrigger = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybe(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybe(e);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            private void maybe(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(SourceRowPanel.this, e.getX(), e.getY());
                }
            }
        };
        addMouseListener(popupTrigger);
        titleLabel.addMouseListener(popupTrigger);
        urlLabel.addMouseListener(popupTrigger);
        infoLabel.addMouseListener(popupTrigger);

        update(rec.snapshot());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Theme t = ThemeManager.current();
        int w = getWidth();
        int h = getHeight();
        int r = t.cardRadius();
        for (int i = 2; i >= 1; i--) {
            g2.setColor(t.shadow());
            g2.fillRoundRect(1, 2 + i, w - 3, h - 6, r * 2, r * 2);
        }
        g2.setColor(t.cardBackground());
        g2.fillRoundRect(1, 2, w - 3, h - 6, r * 2, r * 2);
        g2.setColor(hover ? t.accent() : t.cardBorder());
        g2.drawRoundRect(1, 2, w - 3, h - 6, r * 2, r * 2);
        g2.dispose();
    }

    private void onButton() {
        switch (rec.state()) {
            case FOUND -> actions.enqueue(rec, selectedQualityHeight());
            case QUEUED, DOWNLOADING -> actions.cancel(rec);
            case DONE -> actions.openOutput(rec.outPath());
            case ERROR, CANCELLED -> actions.retry(rec);
        }
    }

    /** Must be called on the EDT. */
    public void update(SourceRecord.View v) {
        Theme t = ThemeManager.current();
        String title = v.pageTitle() == null || v.pageTitle().isBlank()
                ? "Video" : v.pageTitle();
        titleLabel.setText(title);
        titleLabel.setFont(t.titleFont());
        titleLabel.setForeground(t.textPrimary());

        // YouTube rows are keyed by "yt:<videoId>" (dedup), but the user sees the watch URL
        String url = v.kind() == SourceRecord.Kind.YOUTUBE && v.pageUrl() != null
                ? v.pageUrl() : v.canonicalUrl();
        urlLabel.setText(url.length() > URL_CLIP ? url.substring(0, URL_CLIP) + "…" : url);
        urlLabel.setToolTipText(url);
        urlLabel.setFont(t.baseFont());
        urlLabel.setForeground(t.textSecondary());

        updateQualityOptions(v.qualities());
        qualitySelector.setVisible(!displayedQualities.isEmpty());
        qualitySelector.setEnabled(v.state() == SourceRecord.State.FOUND);

        infoLabel.setText(stateText(v));
        infoLabel.setFont(t.baseFont());
        infoLabel.setForeground(switch (v.state()) {
            case FOUND -> t.accent();
            case QUEUED -> t.textPrimary();
            case DOWNLOADING -> t.statusDownloading();
            case DONE -> t.statusDone();
            case ERROR -> t.statusError();
            case CANCELLED -> t.statusCancelled();
        });
        updateButtonText(v.state());
    }

    private void updateQualityOptions(List<Integer> qualities) {
        List<QualityOption> options = new ArrayList<>();
        if (qualities != null) {
            for (Integer height : qualities) {
                if (height != null && height > 0) {
                    options.add(new QualityOption(height, height + "p"));
                }
            }
        }
        if (!options.isEmpty()) {
            options.add(0, new QualityOption(0, "best available"));
        }
        if (options.equals(displayedQualities)) {
            return;
        }

        int selectedHeight = selectedQualityHeight();
        displayedQualities = List.copyOf(options);
        qualitySelector.setModel(new DefaultComboBoxModel<>(
                displayedQualities.toArray(QualityOption[]::new)));
        for (int i = 0; i < displayedQualities.size(); i++) {
            if (displayedQualities.get(i).height() == selectedHeight) {
                qualitySelector.setSelectedIndex(i);
                return;
            }
        }
        qualitySelector.setSelectedIndex(0);
    }

    private int selectedQualityHeight() {
        Object selected = qualitySelector.getSelectedItem();
        return selected instanceof QualityOption option ? option.height() : 0;
    }

    private void updateButtonText(SourceRecord.State state) {
        button.setText(switch (state) {
            case FOUND -> selectedDownloadLabel();
            case QUEUED, DOWNLOADING -> "Cancel";
            case DONE -> "Open Folder";
            case ERROR, CANCELLED -> "Retry";
        });
    }

    private String selectedDownloadLabel() {
        if (displayedQualities.isEmpty()) {
            return "Download";
        }
        Object selected = qualitySelector.getSelectedItem();
        String label = selected instanceof QualityOption option ? option.label() : "best available";
        return label.equals("best available")
                ? "Download best" : "Download " + label;
    }

    private static String qualitiesText(List<Integer> qualities) {
        if (qualities == null || qualities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer h : qualities) {
            if (h == null || h <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(h).append('p');
        }
        return sb.toString();
    }

    private static String stateText(SourceRecord.View v) {
        switch (v.state()) {
            case FOUND: {
                String q = qualitiesText(v.qualities());
                return q.isEmpty() ? "found"
                        : "Found · qualities: " + q;
            }
            case QUEUED:
                return "queued";
            case DOWNLOADING: {
                StringBuilder sb = new StringBuilder("downloading");
                // HLS: segments; YouTube: range-chunks of the signed stream
                if ((v.kind() == SourceRecord.Kind.HLS || v.kind() == SourceRecord.Kind.YOUTUBE)
                        && v.segmentsTotal() > 0) {
                    sb.append(" · segments ").append(v.segmentsDone())
                            .append(" of ").append(v.segmentsTotal())
                            .append(" (").append(v.segmentsDone() * 100 / v.segmentsTotal()).append("%)");
                }
                sb.append(" · ").append(fmtMb(v.bytesReceived()));
                if (v.kind() == SourceRecord.Kind.MP4 && v.sizeBytes() > 0) {
                    sb.append(" of ").append(fmtMb(v.sizeBytes()))
                            .append(" (").append(v.bytesReceived() * 100 / v.sizeBytes()).append("%)");
                }
                double sec = Math.max(v.elapsedMs(), 1) / 1000.0;
                sb.append(" · ").append(String.format("%.1f MB/s", v.bytesReceived() / 1048576.0 / sec));
                if (v.stage() != null && !v.stage().isBlank()) {
                    sb.append(" · ").append(v.stage());
                }
                return sb.toString();
            }
            case DONE:
                return "done · " + (v.outPath() == null ? "" : v.outPath());
            case ERROR:
                return "error · " + (v.message() == null ? "" : v.message());
            case CANCELLED:
                return "cancelled";
        }
        return v.state().toString();
    }

    private static String fmtMb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
