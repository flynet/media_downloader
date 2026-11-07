package com.example.hlsgrabber;

import com.example.hlsgrabber.util.FileNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Тесты FileNames (TZ раздел 9): санитизация, обрезка длины, конфликты имён.
 *  FileNames tests (TZ section 9): sanitization, length clipping, name conflicts. */
class FileNamesTest {

    @Test
    void sanitizesIllegalCharacters() {
        // каждый запрещённый в Windows символ -> '_', концевые точки/пробелы снимаются
        // every Windows-illegal char -> '_', trailing dots/spaces stripped
        assertEquals("a_b________c", FileNames.sanitize("a<b>:\"/\\|?*c"));
    }

    @Test
    void sanitizeReplacesAllIllegalChars() {
        String s = FileNames.sanitize("a<b>:\"/\\|?*c.mp4");
        assertFalse(s.contains("<"));
        assertFalse(s.contains(">"));
        assertFalse(s.contains(":"));
        assertFalse(s.contains("\""));
        assertFalse(s.contains("/"));
        assertFalse(s.contains("\\"));
        assertFalse(s.contains("|"));
        assertFalse(s.contains("?"));
        assertFalse(s.contains("*"));
        assertTrue(s.endsWith(".mp4"));
    }

    @Test
    void sanitizeHandlesReservedNames() {
        assertEquals("video_CON", FileNames.sanitize("CON"));
        assertEquals("video_LPT1", FileNames.sanitize("lpt1"));
    }

    @Test
    void sanitizeStripsTrailingDotsAndSpaces() {
        assertEquals("name", FileNames.sanitize("name...   "));
    }

    @Test
    void clipRespectsMaxPath() {
        String dir = "C:\\Users\\Home\\Downloads\\longdir_with_plenty_20_chars_dir\\sub";
        String base = "x".repeat(500);
        String clipped = FileNames.clip(dir, base, ".mp4");
        assertTrue(clipped.length() <= 200);
        assertTrue(dir.length() + clipped.length() + ".mp4".length() < 260);
    }

    @Test
    void conflictsGetNumericSuffixes(@TempDir Path tmp) throws Exception {
        File f1 = FileNames.uniquePath(tmp.toFile(), "video", ".mp4");
        assertEquals("video.mp4", f1.getName());
        f1.createNewFile();
        File f2 = FileNames.uniquePath(tmp.toFile(), "video", ".mp4");
        assertEquals("video (1).mp4", f2.getName());
        f2.createNewFile();
        File f3 = FileNames.uniquePath(tmp.toFile(), "video", ".mp4");
        assertEquals("video (2).mp4", f3.getName());
    }

    @Test
    void buildCombinesSanitizeClipUnique(@TempDir Path tmp) throws Exception {
        File f = FileNames.build(tmp.toFile(), "My <Video>: Title", ".mp4");
        String clipped = FileNames.clip(tmp.toFile().getPath(), FileNames.sanitize("My <Video>: Title"), ".mp4");
        assertTrue(f.getName().startsWith(clipped));
        assertTrue(f.getName().endsWith(".mp4"));
        f.createNewFile();
        File g = FileNames.build(tmp.toFile(), "My <Video>: Title", ".mp4");
        assertTrue(g.getName().contains(" (1)"));
    }
}