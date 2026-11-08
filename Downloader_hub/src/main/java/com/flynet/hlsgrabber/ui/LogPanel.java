package com.flynet.hlsgrabber.ui;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Bottom GUI section: log (last {@value #MAX_LINES} lines, trim on overflow),
 * Clear and Open folder buttons, autoscroll.
 *
 * Log is written via SLF4J (file + console + GUI appender); all text
 * changes go through SwingUtilities.invokeLater only.
 */
public class LogPanel extends JPanel {

    static final int MAX_LINES = 5000;
    static final int TRIM_KEEP = 1000;

    private final JTextArea area = new JTextArea();
    private final JCheckBox autoscroll = new JCheckBox("Autoscroll", true);
    private final JLabel statusLabel = new JLabel("Initializing…", SwingConstants.LEFT);
    private final Supplier<Path> dirSupplier;

    public LogPanel(Supplier<Path> dirSupplier) {
        super(new BorderLayout());
        this.dirSupplier = dirSupplier;
        area.setEditable(false);
        area.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        JPanel row = new JPanel(new GridLayout(1, 4));
        row.putClientProperty("role", "panel");
        row.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 12));
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> area.setText(""));
        JButton open = new JButton("Open folder");
        open.addActionListener(e -> openLogDir());
        statusLabel.putClientProperty("role", "accent");
        ThemeManager.applyRoleStyles(statusLabel);
        row.add(clear);
        row.add(open);
        row.add(autoscroll);
        row.add(statusLabel);
        add(row, BorderLayout.NORTH);
    }

    /** Called from SwingLogAppender; append on the EDT. */
    public void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            boolean atEnd = isCaretAtEnd();
            area.append(line + "\n");
            trim();
            if (autoscroll.isSelected() && atEnd) {
                area.setCaretPosition(area.getDocument().getLength());
            }
        });
    }

    /** Status line — launch/browser state, EDT-safe. */
    public void setStatusLine(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text == null ? "" : text));
    }

    /** Status line snapshot — for the smoke harness. */
    public String statusText() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            // EDT already blocked the caller
        }
        return statusLabel.getText();
    }

    /** Current console text (EDT-safe snapshot; used by the GUI smoke harness). */
    public String textSnapshot() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            // EDT already blocked the caller; read as-is
        }
        return area.getText();
    }

    private boolean isCaretAtEnd() {
        return area.getCaretPosition() >= area.getDocument().getLength() - 1;
    }

    private void trim() {
        int lines = area.getLineCount();
        if (lines <= MAX_LINES) {
            return;
        }
        int remove = lines - TRIM_KEEP;
        try {
            int end = area.getLineStartOffset(remove);
            area.replaceRange("", 0, end);
        } catch (BadLocationException e) {
            // ignore: lines have already changed
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
}
