package com.flynet.hlsgrabber.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings persisted in %APPDATA%\hlsgrabber\settings.properties (TZ section 4.3/6.1):
 * targetHeight=1080 (CLI fallback; GUI uses the row quality selector), minSizeBytes,
 * parallelSegments, downloadDir. No settings UI.
 */
public class Settings {

    public int targetHeight = 1080;
    public long minSizeBytes = 1048576;       // ~1 MB, TZ 5.1
    public int parallelSegments = 6;          // TZ 6.1
    public String downloadDir;
    public String userAgent;                  // null = browser default UA

    public static Path settingsFile() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null
                ? Path.of(appData, "hlsgrabber")
                : Path.of(System.getProperty("user.home"), ".hlsgrabber");
        return dir.resolve("settings.properties");
    }

    public static Settings load() {
        Settings s = new Settings();
        Path f = settingsFile();
        if (Files.exists(f)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                return s;
            }
            s.targetHeight = Integer.parseInt(p.getProperty("targetHeight", "1080"));
            s.minSizeBytes = Long.parseLong(p.getProperty("minSizeBytes", "1048576"));
            s.parallelSegments = Integer.parseInt(p.getProperty("parallelSegments", "6"));
            s.downloadDir = p.getProperty("downloadDir", null);
            s.userAgent = p.getProperty("userAgent", null);
        }
        return s;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("targetHeight", String.valueOf(targetHeight));
        p.setProperty("minSizeBytes", String.valueOf(minSizeBytes));
        p.setProperty("parallelSegments", String.valueOf(parallelSegments));
        if (downloadDir != null) {
            p.setProperty("downloadDir", downloadDir);
        }
        if (userAgent != null) {
            p.setProperty("userAgent", userAgent);
        }
        Path f = settingsFile();
        try {
            Files.createDirectories(f.getParent());
            try (var out = Files.newOutputStream(f)) {
                p.store(out, "hls-grabber settings");
            }
        } catch (IOException e) {
            // best-effort persistence
        }
    }
}