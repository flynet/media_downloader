package com.example.hlsgrabber.ui;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Нижняя секция GUI: лог (последние {@value #MAX_LINES} строк, trim при
 * переполнении), кнопки «Очистить» и «Открыть папка», автопрокрутка.
 *
 * <p>Лог пишется через SLF4J (файл + консоль + GUI-аппендер); все
 * изменения текста — только через SwingUtilities.invokeLater.
 */
public class LogPanel extends JPanel {

    static final int MAX_LINES = 5000;
    static final int TRIM_KEEP = 1000;

    private final JTextArea area = new JTextArea();
    private final JCheckBox autoscroll = new JCheckBox("Автокрутка", true);
    private final JLabel statusLabel = new JLabel("Инициализация…", SwingConstants.LEFT);
    private final Supplier<Path> dirSupplier;

    public LogPanel(Supplier<Path> dirSupplier) {
        super(new BorderLayout());
        this.dirSupplier = dirSupplier;
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(area);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        JPanel row = new JPanel(new GridLayout(1, 4));
        JButton clear = new JButton("Очистить");
        clear.addActionListener(e -> area.setText(""));
        JButton open = new JButton("Открыть папку");
        open.addActionListener(e -> openLogDir());
        row.add(clear);
        row.add(open);
        row.add(autoscroll);
        row.add(statusLabel);
        add(row, BorderLayout.NORTH);
    }

    /** Вызывается из SwingLogAppender; append на EDT. */
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

    /** Статусная строка — состояние запуска/браузера, EDT-safe. */
    public void setStatusLine(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text == null ? "" : text));
    }

    /** Снимок статусной строки — для смоук-харнесса. */
    public String statusText() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            // EDT уже заблокирован вызывающим потоком
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
            // ignore: строки уже изменились
        }
    }

    private void openLogDir() {
        try {
            Path dir = dirSupplier.get();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                appendLine("Открыть папку нельзя: Desktop не поддерживается");
            }
        } catch (Exception e) {
            appendLine("Не могу открыть папку: " + e.getMessage());
        }
    }
}
