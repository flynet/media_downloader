package com.example.hlsgrabber.ui;

import com.example.hlsgrabber.manifest.SourceRecord;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * One source row (TZ 5.2): title, clipped URL (full in tooltip), state line with
 * progress/speed/stage, and a state-dependent action button.
 * Right-click: "Копировать ссылку".
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
    private final JButton button = new JButton("Скачать");
    private final JComboBox<QualityOption> qualitySelector = new JComboBox<>();
    private List<QualityOption> displayedQualities = List.of();

    public SourceRowPanel(SourceRecord rec, RowActions actions) {
        this.rec = rec;
        this.actions = actions;

        setLayout(new BorderLayout(8, 2));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 180)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setAlignmentX(LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 86));

        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        urlLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        urlLabel.setForeground(new Color(110, 110, 110));
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        add(titleLabel, BorderLayout.NORTH);
        add(urlLabel, BorderLayout.CENTER);
        add(infoLabel, BorderLayout.SOUTH);

        JPanel east = new JPanel();
        east.setLayout(new BoxLayout(east, BoxLayout.Y_AXIS));
        qualitySelector.setPreferredSize(new Dimension(140, qualitySelector.getPreferredSize().height));
        qualitySelector.setMaximumSize(new Dimension(140, qualitySelector.getPreferredSize().height));
        qualitySelector.setAlignmentX(Component.CENTER_ALIGNMENT);
        qualitySelector.addActionListener(e -> updateButtonText(rec.state()));
        east.add(qualitySelector);
        button.addActionListener(e -> onButton());
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
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
            case FOUND -> actions.enqueue(rec, selectedQualityHeight());
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

        updateQualityOptions(v.qualities());
        qualitySelector.setVisible(!displayedQualities.isEmpty());
        qualitySelector.setEnabled(v.state() == SourceRecord.State.FOUND);

        infoLabel.setText(stateText(v));
        infoLabel.setForeground(switch (v.state()) {
            case DOWNLOADING -> new Color(30, 90, 200);
            case DONE -> new Color(20, 130, 45);
            case ERROR -> new Color(190, 40, 40);
            case CANCELLED -> new Color(120, 120, 120);
            default -> Color.BLACK;
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
            options.add(0, new QualityOption(0, "Лучшее доступное"));
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
            case QUEUED, DOWNLOADING -> "Отмена";
            case DONE -> "Открыть папку";
            case ERROR, CANCELLED -> "Повторить";
        });
    }

    private String selectedDownloadLabel() {
        if (displayedQualities.isEmpty()) {
            return "Скачать";
        }
        Object selected = qualitySelector.getSelectedItem();
        String label = selected instanceof QualityOption option ? option.label() : "Лучшее доступное";
        return label.equals("Лучшее доступное") ? "Скачать лучшее" : "Скачать " + label;
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
