package com.flynet.hlsgrabber.ui.fx;

import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * Main window (FX mirror of Swing ui/MainWindow): header (title, folder, theme
 * toggle), split 40/60 list over log, native title bar. Window title stays the
 * static "HLS Grabber" so MicaHelper can FindWindow it; the folder shows in the
 * header label only. Closing with active downloads confirms, then the app exits
 * when the queue drains (idle hook wired in FxGuiApp).
 */
public final class FxMainWindow {

    private static final String TITLE = "HLS Grabber";

    private final Stage stage;
    private final DownloadQueue queue;
    private final Label folderLabel = new Label();
    private final Button themeToggle;
    private final LogFx logFx;

    public FxMainWindow(Stage stage, SourceListFx list, LogFx log, Settings settings, DownloadQueue queue) {
        this.stage = stage;
        this.queue = queue;
        this.logFx = log;
        stage.setTitle(TITLE);

        Label titleLabel = new Label(TITLE);
        ThemeBridge.applyRole(titleLabel, "titleAccent");
        folderLabel.setText("Folder: "
                + (settings.downloadDir == null ? "(not set)" : settings.downloadDir));
        ThemeBridge.applyRole(folderLabel, "secondary");

        themeToggle = new Button("Theme: "
                + (ThemeBridge.mode() == ThemeBridge.Mode.DARK ? "Dark" : "Light"));
        ThemeBridge.register(themeToggle, p ->
                "-fx-background-color: " + p.accent() + ";"
                        + " -fx-text-fill: #FFFFFF;"
                        + " -fx-background-radius: 8;"
                        + " -fx-font-family: \"Inter Medium\", \"Inter\", \"Segoe UI\";"
                        + " -fx-font-size: 13;");
        themeToggle.setOnAction(e -> {
            ThemeBridge.Mode m = ThemeBridge.toggle();
            themeToggle.setText("Theme: " + (m == ThemeBridge.Mode.DARK ? "Dark" : "Light"));
            MicaHelper.apply(stage, m == ThemeBridge.Mode.DARK);
        });

        Region spacer = new Region();
        HBox header = new HBox(10, titleLabel, folderLabel, spacer, themeToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        header.setPadding(new Insets(10, 14, 4, 14));
        ThemeBridge.register(header, p -> "-fx-background-color: " + p.panel() + ";");

        SplitPane split = new SplitPane(list, logFx);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.4);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(split);
        ThemeBridge.register(root, p -> "-fx-background-color: " + p.panel() + ";");

        Scene scene = new Scene(root, 1100, 760);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            e.consume();
            close();
        });
    }

    private void close() {
        if (queue.hasActiveTasks()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(TITLE);
            alert.setHeaderText(null);
            alert.setContentText("Downloads in progress. Close the window? Downloads "
                    + "continue; the app exits when the queue drains.");
            alert.initOwner(stage);
            Optional<ButtonType> r = alert.showAndWait();
            if (r.isEmpty() || r.get() != ButtonType.YES) {
                return;
            }
        }
        queue.setClosing(true);
        if (!queue.hasActiveTasks()) {
            System.exit(0);
        }
    }

    /** Reflects the chosen download folder in the header (FX-thread-safe). */
    public void setFolder(String dir) {
        Platform.runLater(() ->
                folderLabel.setText("Folder: " + (dir == null ? "(not set)" : dir)));
    }

    /** Status line forwarding to the log panel. */
    public void statusLine(String text) {
        logFx.setStatusLine(text);
    }

    public Stage stage() {
        return stage;
    }

    public Scene scene() {
        return stage.getScene();
    }

    public Button themeToggle() {
        return themeToggle;
    }

    public void show() {
        stage.show();
    }
}
