package com.flynet.hlsgrabber.ui.fx;

import com.flynet.hlsgrabber.manifest.SourceRecord;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * One source row as a rounded card (FX mirror of Swing SourceRowPanel): title,
 * clipped URL (full in tooltip), state line with progress/speed/stage, and a
 * state-dependent primary action button. Flat "Apple Glass" card: rounded fill,
 * 1px border (accent on hover); colors follow the active theme on toggle.
 * Right-click: "Copy link".
 */
public final class SourceRowFx extends HBox {

    public interface RowActions {
        void enqueue(SourceRecord rec, int qualityHeight);
        void cancel(SourceRecord rec);
        void retry(SourceRecord rec);
        void openOutput(String outPath);
    }

    public record QualityOption(int height, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final int URL_CLIP = 100;

    private final SourceRecord rec;
    private final RowActions actions;
    private final Label titleLabel = new Label();
    private final Label urlLabel = new Label();
    private final Label infoLabel = new Label();
    private final Button button = new Button("Download");
    private final ComboBox<QualityOption> qualitySelector = new ComboBox<>();
    private List<QualityOption> displayedQualities = List.of();
    private boolean hover;
    private SourceRecord.State lastState = SourceRecord.State.FOUND;

    public SourceRowFx(SourceRecord rec, RowActions actions) {
        this.rec = rec;
        this.actions = actions;

        setSpacing(8);
        setPadding(new Insets(8, 14, 10, 14));
        setAlignment(Pos.CENTER_LEFT);
        setMaxHeight(88);

        VBox left = new VBox(2, titleLabel, urlLabel, infoLabel);
        HBox.setHgrow(left, Priority.ALWAYS);
        Region gap = new Region();
        VBox right = new VBox(10, qualitySelector, button);
        qualitySelector.setPrefWidth(140);
        button.setMaxWidth(160);
        button.setAlignment(Pos.CENTER);
        getChildren().addAll(left, gap, right);

        ThemeBridge.applyRole(titleLabel, "title");
        ThemeBridge.applyRole(urlLabel, "secondary");

        qualitySelector.setOnAction(e -> updateButtonText(rec.state()));
        button.setOnAction(e -> onButton());

        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem("Copy link");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(rec.canonicalUrl);
            Clipboard.getSystemClipboard().setContent(c);
        });
        menu.getItems().add(copy);
        setOnContextMenuRequested(e -> menu.show(getScene().getWindow(), e.getScreenX(), e.getScreenY()));

        setOnMouseEntered(e -> {
            hover = true;
            applyCard();
        });
        setOnMouseExited(e -> {
            hover = false;
            applyCard();
        });

        ThemeBridge.register(this, p -> cardStyle(p));
        ThemeBridge.register(infoLabel, this::infoStyle);
        // flat white field for the quality combo, accent-filled primary button
        ThemeBridge.register(qualitySelector, p -> "-fx-background-color: " + p.field() + ", " + p.field()
                + "; -fx-background-radius: 8; -fx-background-insets: 0, 1; -fx-padding: 6 10; -fx-font-size: 13;"
                + " -fx-text-fill: " + p.textPrimary() + "; -fx-prompt-text-fill: " + p.textSecondary() + ";");
        ThemeBridge.register(button, p -> "-fx-background-color: " + p.accent() + ", " + p.accent()
                + "; -fx-background-radius: 8; -fx-text-fill: white; -fx-font-size: 12;");

        update(rec.snapshot());
    }

    private String cardStyle(ThemeBridge.Palette p) {
        String border = hover ? p.accent() : p.cardBorder();
        return "-fx-background-color: " + p.card()
                + "; -fx-background-radius: " + p.cardRadius()
                + "; -fx-border-color: " + border
                + "; -fx-border-width: 1; -fx-border-radius: " + p.cardRadius();
    }

    private void applyCard() {
        setStyle(cardStyle(ThemeBridge.palette(ThemeBridge.mode())));
    }

    private String infoStyle(ThemeBridge.Palette p) {
        String color = switch (lastState) {
            case FOUND -> p.accent();
            case QUEUED -> p.textPrimary();
            case DOWNLOADING -> p.accent();
            case DONE -> p.statusDone();
            case ERROR -> p.statusError();
            case CANCELLED -> p.statusCancelled();
        };
        return "-fx-font-family: \"Inter Medium\", \"Inter\", \"Segoe UI\"; -fx-font-size: 12px; -fx-text-fill: " + color;
    }

    private void onButton() {
        switch (rec.state()) {
            case FOUND -> actions.enqueue(rec, selectedQualityHeight());
            case QUEUED, DOWNLOADING -> actions.cancel(rec);
            case DONE -> actions.openOutput(rec.outPath());
            case ERROR, CANCELLED -> actions.retry(rec);
        }
    }

    /** Must be called on the FX app thread. */
    public void update(SourceRecord.View v) {
        lastState = v.state();
        String title = v.pageTitle() == null || v.pageTitle().isBlank()
                ? "Video" : v.pageTitle();
        titleLabel.setText(title);

        // YouTube rows are keyed by "yt:<videoId>" (dedup), but the user sees the watch URL
        String url = v.kind() == SourceRecord.Kind.YOUTUBE && v.pageUrl() != null
                ? v.pageUrl() : v.canonicalUrl();
        urlLabel.setText(url.length() > URL_CLIP ? url.substring(0, URL_CLIP) + "…" : url);
        urlLabel.setTooltip(new Tooltip(url));

        updateQualityOptions(v.qualities());
        qualitySelector.setVisible(!displayedQualities.isEmpty());
        qualitySelector.setDisable(v.state() != SourceRecord.State.FOUND);

        infoLabel.setText(stateText(v));
        infoLabel.setStyle(infoStyle(ThemeBridge.palette(ThemeBridge.mode())));
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
        qualitySelector.setItems(javafx.collections.FXCollections.observableArrayList(displayedQualities));
        for (int i = 0; i < displayedQualities.size(); i++) {
            if (displayedQualities.get(i).height() == selectedHeight) {
                qualitySelector.getSelectionModel().select(i);
                return;
            }
        }
        qualitySelector.getSelectionModel().select(0);
    }

    private int selectedQualityHeight() {
        QualityOption sel = qualitySelector.getValue();
        return sel != null ? sel.height() : 0;
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
        QualityOption sel = qualitySelector.getValue();
        String label = sel != null ? sel.label() : "best available";
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

    // harness accessors (package-visible tests)
    public Button actionButton() {
        return button;
    }

    public Label urlLabel() {
        return urlLabel;
    }

    public Label titleLabel() {
        return titleLabel;
    }

    public Label infoLabel() {
        return infoLabel;
    }

    public ComboBox<QualityOption> qualitySelector() {
        return qualitySelector;
    }

    public SourceRecord record() {
        return rec;
    }
}
