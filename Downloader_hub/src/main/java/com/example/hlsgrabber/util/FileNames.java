package com.example.hlsgrabber.util;

import java.io.File;
import java.util.Set;

/**
 * Windows-safe именование файлов (TZ 6.1): санитизация, обрезка длины, суффиксы конфликтов /
 * Windows-safe file naming (TZ 6.1): sanitize, length clipping, conflict suffixes.
 */
public final class FileNames {

    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final int MAX_NAME = 200;
    private static final int WINDOWS_MAX_PATH = 260;

    private FileNames() {
    }

    /** Заменяет запрещённые в Windows символы, control-символы, trailing точки/пробелы / Replace characters illegal on Windows, control chars, trailing dots/spaces. */
    public static String sanitize(String name) {
        String s = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_");
        s = s.replaceAll("[. ]+$", "");
        s = s.trim();
        if (s.isEmpty()) {
            s = "video";
        }
        String upper = s.toUpperCase();
        if (RESERVED.contains(upper)) {
            s = "video_" + upper;
        }
        return s;
    }

    /**
     * Обрезает sanitized base name, чтобы dir + name + ext оставались под Windows MAX_PATH /
     * Clip a sanitized base name so dir + name + ext stays under Windows MAX_PATH.
     */
    public static String clip(String dir, String base, String ext) {
        int budget = WINDOWS_MAX_PATH - dir.length() - ext.length() - 10;
        int allowed = Math.min(MAX_NAME, Math.max(1, budget));
        return base.length() <= allowed ? base : base.substring(0, allowed);
    }

    /**
     * Финальный путь для {@code base.ext} в {@code dir}; при конфликте добавляет
     * {@code " (1)"}, {@code " (2)"}, ... /
     * Final path for {@code base.ext} in {@code dir}; on conflict appends {@code " (1)"}, {@code " (2)"}, ...
     */
    public static File uniquePath(File dir, String base, String ext) {
        String b = base;
        File out = new File(dir, b + ext);
        int i = 1;
        while (out.exists()) {
            out = new File(dir, b + " (" + i + ")" + ext);
            i++;
        }
        return out;
    }

    /**
     * One-shot: sanitize + clip + uniqueness, возвращает финальный File /
     * One-shot: sanitize + clip + uniqueness, returns final File.
     */
    public static File build(File dir, String pageName, String ext) {
        String base = clip(dir.getPath(), sanitize(pageName), ext);
        return uniquePath(dir, base, ext);
    }
}