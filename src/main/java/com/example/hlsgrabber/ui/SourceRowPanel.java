package com.example.hlsgrabber.ui;

import com.example.hlsgrabber.manifest.SourceRecord;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * One source row (TZ 5.2): title, clipped URL (full in tooltip), state line with
 * progress/speed/stage, and a state-dependent action button.
 * Right-click: "Копировать ссылку".
 */
public final class SourceRowPanel extends JPanel {

    public interface RowActions {
        void enqueue(SourceRecord rec);
        void cancel(SourceRecord rec);
        void retry(SourceRecord rec);
        void openOutput(String outPath);
    }

    private static final int URL_CLIP = 100;

    private final SourceRecord rec;
    private final RowActions actions;
    private final JLabel titleLabel = new JLabel();
    private final JLabel urlLabel = new JLabel();
    private final JLabel infoLabel = new JLabel();
    private final JButton button = new JButton("Скачать");

    public SourceRowPanel(SourceRecord rec, RowActions actions) {
        this.rec = rec;
        this.actions = actions;

        setLayout(new BorderLayout(8, 2));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 180)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setAlignmentX(LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));

        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        urlLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        urlLabel.setForeground(new Color(110, 110, 110));
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        add(titleLabel, BorderLayout.NORTH);
        add(urlLabel, BorderLayout.CENTER);
        add(infoLabel, BorderLayout.SOUTH);

        JPanel east = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        button.addActionListener(e -> onButton());
        east.add(button);
        add(east, BorderLayout.EAST);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Копировать ссылку");
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

    private void onButton() {
        switch (rec.state()) {
            case FOUND -> actions.enqueue(rec);
            case QUEUED, DOWNLOADING -> actions.cancel(rec);
            case DONE -> actions.openOutput(rec.outPath());
            case ERROR, CANCELLED -> actions.retry(rec);
        }
    }

    /** Must be called on the EDT. */
    public void update(SourceRecord.View v) {
        String title = v.pageTitle() == null || v.pageTitle().isBlank()
                ? "Видео" : v.pageTitle();
        titleLabel.setText(title);

        // YouTube rows are keyed by "yt:<videoId>" (dedup), but the user sees the watch URL
        String url = v.kind() == SourceRecord.Kind.YOUTUBE && v.pageUrl() != null
                ? v.pageUrl() : v.canonicalUrl();
        urlLabel.setText(url.length() > URL_CLIP ? url.substring(0, URL_CLIP) + "…" : url);
        urlLabel.setToolTipText(url);

        infoLabel.setText(stateText(v));
        infoLabel.setForeground(switch (v.state()) {
            case DOWNLOADING -> new Color(30, 90, 200);
            case DONE -> new Color(20, 130, 45);
            case ERROR -> new Color(190, 40, 40);
            case CANCELLED -> new Color(120, 120, 120);
            default -> Color.BLACK;
        });
        button.setText(switch (v.state()) {
            case FOUND -> "Скачать";
            case QUEUED, DOWNLOADING -> "Отмена";
            case DONE -> "Открыть папку";
            case ERROR, CANCELLED -> "Повторить";
        });
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
                return q.isEmpty() ? "Найдено" : "Найдено · качества: " + q;
            }
            case QUEUED:
                return "В очереди";
            case DOWNLOADING: {
                StringBuilder sb = new StringBuilder("Скачивается");
                // HLS: segments; YouTube: range-chunks of the signed stream
                if ((v.kind() == SourceRecord.Kind.HLS || v.kind() == SourceRecord.Kind.YOUTUBE)
                        && v.segmentsTotal() > 0) {
                    sb.append(" · сегменты ").append(v.segmentsDone())
                            .append(" из ").append(v.segmentsTotal())
                            .append(" (").append(v.segmentsDone() * 100 / v.segmentsTotal()).append("%)");
                }
                sb.append(" · ").append(fmtMb(v.bytesReceived()));
                if (v.kind() == SourceRecord.Kind.MP4 && v.sizeBytes() > 0) {
                    sb.append(" из ").append(fmtMb(v.sizeBytes()))
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
                return "Готово · " + (v.outPath() == null ? "" : v.outPath());
            case ERROR:
                return "Ошибка · " + (v.message() == null ? "" : v.message());
            case CANCELLED:
                return "Отменено";
        }
        return v.state().toString();
    }

    private static String fmtMb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
