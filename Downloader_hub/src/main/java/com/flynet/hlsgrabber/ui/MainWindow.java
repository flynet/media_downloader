package com.flynet.hlsgrabber.ui;

import com.flynet.hlsgrabber.config.Settings;
import com.flynet.hlsgrabber.download.DownloadQueue;

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

    private final JLabel folderLabel = new JLabel();

    public MainWindow(SourceListPanel list, LogPanel logPanel, Settings settings, DownloadQueue queue) {
        super("HLS Grabber — folder: " + settings.downloadDir);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.putClientProperty("role", "panel");
        header.setBorder(BorderFactory.createEmptyBorder(10, 14, 4, 14));
        JLabel titleLabel = new JLabel("HLS Grabber");
        titleLabel.putClientProperty("role", "titleAccent");
        ThemeManager.applyRoleStyles(titleLabel);
        folderLabel.setText("Folder: " + (settings.downloadDir == null ? "(not set)" : settings.downloadDir));
        folderLabel.putClientProperty("role", "secondary");
        ThemeManager.applyRoleStyles(folderLabel);
        JButton themeToggle = new JButton("Theme: "
                + (ThemeManager.current() == Theme.DARK ? "Dark" : "Light"));
        themeToggle.addActionListener(e -> {
            ThemeManager.toggle();
            themeToggle.setText("Theme: "
                    + (ThemeManager.current() == Theme.DARK ? "Dark" : "Light"));
        });
        header.add(titleLabel, BorderLayout.WEST);
        header.add(folderLabel, BorderLayout.CENTER);
        header.add(themeToggle, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

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
                            "Downloads in progress. Close the window? Downloads "
                                    + "continue; the app exits when the queue drains.",
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

    /** Reflects the chosen download folder in the header (EDT-safe call sites). */
    public void setFolder(String dir) {
        folderLabel.setText("Folder: " + (dir == null ? "(not set)" : dir));
        setTitle("HLS Grabber — folder: " + dir);
    }
}
