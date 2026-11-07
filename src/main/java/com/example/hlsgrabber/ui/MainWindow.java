package com.example.hlsgrabber.ui;

import com.example.hlsgrabber.config.Settings;
import com.example.hlsgrabber.download.DownloadQueue;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main window (TZ 5.2): JSplitPane vertical 40/60 — source rows on top, console below.
 * Closing with active downloads asks for confirmation; downloads continue in background,
 * the app exits when the queue drains (idle hook wired in App).
 */
public final class MainWindow extends JFrame {

    public MainWindow(SourceListPanel list, LogPanel logPanel, Settings settings, DownloadQueue queue) {
        super("HLS Grabber — папка: " + settings.downloadDir);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, logPanel);
        split.setResizeWeight(0.4);
        split.setDividerLocation(300);
        add(split);

        setSize(1100, 760);
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (queue.hasActiveTasks()) {
                    int r = JOptionPane.showConfirmDialog(MainWindow.this,
                            "Выполняются загрузки. Закрыть окно? Загрузки продолжатся, "
                                    + "приложение завершится, когда очередь опустеет.",
                            "HLS Grabber", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (r != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                queue.setClosing(true);
                if (!queue.hasActiveTasks()) {
                    System.exit(0);
                }
            }
        });
    }
}
