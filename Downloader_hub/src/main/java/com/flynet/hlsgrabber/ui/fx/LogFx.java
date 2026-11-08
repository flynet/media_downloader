package com.flynet.hlsgrabber.ui.fx;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.geometry.Insets;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bottom GUI section: log (last {@value #MAX_LINES} lines, trim on overflow),
 * Clear and Open folder buttons, autoscroll — the FX mirror of Swing LogPanel.
 *
 * <p>Log is written via SLF4J (file + console + {@link GuiLogAppender}); all
 * text changes go through Platform.runLater only. Status line role is accent.
 */
public final class LogFx extends BorderPane {

    static final int MAX_LINES = 5000;
    static final int TRIM_KEEP = 1000;

    private final TextArea area = new TextArea();
    private final CheckBox autoscroll = new CheckBox("Autoscroll");
    private final Label statusLabel = new Label("Initializing…");
    private final Supplier<Path> dirSupplier;

    public LogFx(Supplier<Path> dirSupplier) {
        this.dirSupplier = dirSupplier;
        area.setEditable(false);
        area.setWrapText(false);
        ThemeBridge.register(area, p ->
            ThemeBridge.roleStyle("mono", p)
                    + "; -fx-background-color: " + p.field()
                    + "; -fx-background-radius: " + p.cardRadius()
                    + "; -fx-padding: 10; -fx-text-fill: " + p.textPrimary());

        Button clear = new Button("Clear");
        clear.setOnAction(e -> area.setText(""));
        Button open = new Button("Open folder");
        open.setOnAction(e -> openLogDir());
        autoscroll.setSelected(true);
        ThemeBridge.applyRole(statusLabel, "accent");

        HBox row = new HBox(8, clear, open, autoscroll, statusLabel);
        row.setPadding(new Insets(8, 12, 6, 12));
        ThemeBridge.applyRole(row, "panel");
        setTop(row);
        setCenter(area);
    }

    /** Called from GuiLogAppender; append on the FX app thread. */
    public void appendLine(String line) {
        Platform.runLater(() -> {
            boolean atEnd = isCaretAtEnd();
            area.appendText(line + "\n");
            trim();
            if (autoscroll.isSelected() && atEnd) {
                area.positionCaret(area.getText().length());
            }
        });
    }

    /** Status line — launch/browser state, FX-thread-safe. */
    public void setStatusLine(String text) {
        Platform.runLater(() -> statusLabel.setText(text == null ? "" : text));
    }

    /** Status line snapshot — for the smoke harness. */
    public String statusText() {
        return readOnFx(() -> statusLabel.getText());
    }

    // harness accessors (pixel checks)
    public TextArea logArea() {
        return area;
    }

    public Label statusLabelNode() {
        return statusLabel;
    }

    /** Current console text (FX-thread-safe snapshot; used by the GUI smoke harness). */
    public String textSnapshot() {
        return readOnFx(() -> area.getText());
    }

    private boolean isCaretAtEnd() {
        return area.getCaretPosition() >= area.getText().length() - 1;
    }

    private void trim() {
        String t = area.getText();
        int nl = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                nl++;
            }
        }
        int lines = nl + 1;
        if (lines <= MAX_LINES) {
            return;
        }
        int remove = lines - TRIM_KEEP;   // remove the first `remove` lines
        int seen = 0;
        int idx = -1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                seen++;
                if (seen == remove) {
                    idx = i + 1;
                    break;
                }
            }
        }
        if (idx >= 0) {
            area.replaceText(0, idx, "");
        }
    }

    private void openLogDir() {
        try {
            Path dir = dirSupplier.get();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                appendLine("Cannot open folder: Desktop not supported");
            }
        } catch (Exception e) {
            appendLine("Cannot open folder: " + e.getMessage());
        }
    }

    /** Read node state on the FX app thread (harnesses run on it). */
    static String readOnFx(Callable<String> c) {
        if (Platform.isFxApplicationThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                return null;
            }
        }
        FutureTask<String> ft = new FutureTask<>(c);
        Platform.runLater(ft);
        try {
            return ft.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
