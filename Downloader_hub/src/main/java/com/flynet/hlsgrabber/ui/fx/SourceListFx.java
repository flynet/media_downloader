package com.flynet.hlsgrabber.ui.fx;

import com.flynet.hlsgrabber.browser.BrowserLauncher;
import com.flynet.hlsgrabber.download.DownloadQueue;
import com.flynet.hlsgrabber.manifest.SourceRecord;
import com.flynet.hlsgrabber.manifest.SourceRegistry;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * URL bar + source list (FX mirror of the Swing ui/SourceListPanel). Rows appear
 * via the registry listener; refresh is coalesced on the FX thread by a 700 ms
 * pulse (Swing Timer mirror). Open URL runs off-thread, exactly like Swing.
 */
public final class SourceListFx extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(SourceListFx.class);

    private final DownloadQueue queue;
    private final SourceRegistry registry;
    private final AtomicReference<BrowserLauncher> launcher;

    private final TextField urlField = new TextField();
    private final Button openUrl = new Button("Open URL");
    private final VBox listArea = new VBox(6);
    private final Map<String, SourceRowFx> rows = new LinkedHashMap<>();
    private final ScrollPane scroll = new ScrollPane();

    public SourceListFx(SourceRegistry registry, DownloadQueue queue, AtomicReference<BrowserLauncher> launcher) {
        this.queue = queue;
        this.registry = registry;
        this.launcher = launcher;

        urlField.setPromptText("URL");
        ThemeBridge.register(urlField, p ->
                "-fx-background-color: " + p.field() + ";"
                        + " -fx-background-radius: 8;"
                        + " -fx-padding: 8 10 8 10;"
                        + " -fx-text-fill: " + p.textPrimary() + ";"
                        + " -fx-font-family: \"Inter Medium\", \"Inter\", \"Segoe UI\";"
                        + " -fx-font-size: 13;"
                        + " -fx-prompt-text-fill: " + p.textSecondary() + ";");

        openUrl.setDisable(true);
        openUrl.setTooltip(new Tooltip("browser not ready yet"));
        ThemeBridge.register(openUrl, p ->
                "-fx-background-color: " + p.accent() + ";"
                        + " -fx-text-fill: #FFFFFF;"
                        + " -fx-background-radius: 8;"
                        + " -fx-font-family: \"Inter Medium\", \"Inter\", \"Segoe UI\";"
                        + " -fx-font-size: 13;");
        openUrl.setOnAction(e -> openUrlClicked());
        HBox.setHgrow(urlField, Priority.ALWAYS);

        HBox top = new HBox(8, urlField, openUrl);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10, 14, 6, 14));
        ThemeBridge.register(top, p -> "-fx-background-color: " + p.panel() + ";");

        listArea.setPadding(new Insets(4, 10, 10, 10));
        ThemeBridge.register(listArea, p -> "-fx-background-color: " + p.panel() + ";");

        scroll.setContent(listArea);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        ThemeBridge.register(scroll, p ->
                "-fx-background-color: " + p.panel() + ";"
                        + " -fx-background-radius: 12;"
                        + " -fx-border-width: 0;");

        setTop(top);
        setCenter(scroll);
        ThemeBridge.register(this, p -> "-fx-background-color: " + p.panel() + ";");

        Timeline refresh = new Timeline(new KeyFrame(Duration.millis(700), e -> refresh()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();

        registry.addListener(rec -> Platform.runLater(this::refresh));
        Platform.runLater(this::refresh);
    }

    private void openUrlClicked() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            return;
        }
        new Thread(() -> {
            BrowserLauncher l = launcher.get();
            if (l == null) {
                log.info("browser not ready — URL not opened: {}", url);
                return;
            }
            try {
                l.openPage(url);
            } catch (Exception e) {
                log.warn("failed to open {}: {}", url, e.toString());
            }
        }, "open-url").start();
    }

    /** Harness hook: enable/disable the Open URL button and its tooltip. */
    public void setOpenEnabled(boolean enabled) {
        Platform.runLater(() -> {
            openUrl.setDisable(!enabled);
            openUrl.setTooltip(new Tooltip(
                    enabled ? "open URL in the browser" : "browser not ready yet"));
        });
    }

    /** Actions a row can perform, wired to the queue and the desktop. */
    private final class RowAct implements SourceRowFx.RowActions {
        @Override public void enqueue(SourceRecord rec, int qualityHeight) {
            queue.enqueue(rec, qualityHeight);
        }
        @Override public void cancel(SourceRecord rec) {
            queue.cancel(rec);
        }
        @Override public void retry(SourceRecord rec) {
            queue.retry(rec);
        }
        @Override public void openOutput(String outPath) {
            try {
                Desktop.getDesktop().open(Path.of(outPath).toFile());
            } catch (Exception e) {
                log.warn("cannot open folder: {}", e.toString());
            }
        }
    }

    private void refresh() {
        List<SourceRecord> recs = registry.records();
        List<Node> ordered = new ArrayList<>();
        for (SourceRecord rec : recs) {
            SourceRowFx row = rows.get(rec.canonicalUrl);
            if (row == null) {
                row = new SourceRowFx(rec, new RowAct());
                rows.put(rec.canonicalUrl, row);
            }
            row.update(rec.snapshot());
            ordered.add(row);
        }
        for (SourceRowFx row : rows.values()) {
            if (!ordered.contains(row)) {
                ThemeBridge.unregister(row);
            }
        }
        listArea.getChildren().setAll(ordered);
    }

    // harness accessors
    public TextField urlField() { return urlField; }
    public Button openUrlButton() { return openUrl; }
    public VBox listArea() { return listArea; }
    public void attachLauncher(BrowserLauncher l) { launcher.set(l); }
}
