package com.example.hlsgrabber;

import com.example.hlsgrabber.browser.BrowserLauncher;
import com.example.hlsgrabber.config.Settings;
import com.example.hlsgrabber.download.DownloadQueue;
import com.example.hlsgrabber.ffmpeg.FfmpegRunner;
import com.example.hlsgrabber.manifest.SourceRecord;
import com.example.hlsgrabber.manifest.SourceRegistry;
import com.example.hlsgrabber.ui.SourceListPanel;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless UI check: builds the real SourceListPanel (timer included), injects fake
 * registry records, and verifies that rows render on the EDT (title / clipped URL /
 * state line). Run with -Djava.awt.headless=true.
 *
 * Новая семантика (TZ 9 строка 192 + список качеств):
 * - записи остаются FOUND, пока пользователь не нажмёт «Скачать» (авто-очереди нет);
 * - в строке FOUND показывается список качеств ("Найдено · качества: 1080p, 720p, 480p");
 * - клик по кнопке строки ставит запись в очередь (состояние уходит из FOUND).
 *
 * Выход: 0 = проверки пройдены, 1 = ошибка.
 *
 * New semantics (TZ 9 line 192 + quality list):
 * - records stay FOUND until the user clicks "Скачать" (no auto-enqueue);
 * - FOUND row shows the available quality list ("Найдено · качества: 1080p, 720p, 480p");
 * - clicking the row button enqueues the record (state leaves FOUND).
 *
 * Exit: 0 = checks pass, 1 = failure.
 */
public class GuiUiCheck {

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.load();
        settings.minSizeBytes = 1_048_576L;
        settings.parallelSegments = 4;
        settings.targetHeight = 1080;
        settings.downloadDir = "D:/Java/Downloader/spikes/r1-r2/ui-out";

        SourceRegistry registry = new SourceRegistry(settings.minSizeBytes);
        FfmpegRunner ffmpeg = new FfmpegRunner();
        DownloadQueue queue = new DownloadQueue(registry, settings, ffmpeg);
        AtomicReference<BrowserLauncher> launcher = new AtomicReference<>();
        queue.setLauncher(null);
        queue.setIdleHook(() -> { /* no-op: no real launcher in headless */ });

        SourceListPanel panel = new SourceListPanel(registry, queue, launcher);

        // внедряем записи, имитируя вывод sniffера (мелкий файл отсекается min-size)
        // inject records, mimicking sniffer output (small file is filtered by min-size)
        registry.add("https://cdn.example/vid/master.m3u8?token=abc", SourceRecord.Kind.HLS,
                "https://site.example/watch?v=1", "Test Video Title", 0);
        registry.add("https://cdn.example/vid/file.mp4", SourceRecord.Kind.MP4,
                "https://site.example/watch?v=1", "Test Video Title", 8_000_000L);
        registry.add("https://cdn.example/vid/small.mp4", SourceRecord.Kind.MP4,
                "https://site.example/watch?v=1", "Test Video Title", 500_000L);
        registry.add("https://cdn.example/vid/seg-1-v1-a1.m4s?e=179", SourceRecord.Kind.MP4,
                "https://site.example/watch?v=1", "Test Video Title", 3_000_000L);

        // список качеств внедряется так, как снафер извлёк бы его из мастер-плейлиста
        // quality list injected as the sniffer would extract it from a master playlist
        SourceRecord hlsRec = registry.records().stream()
                .filter(r -> r.canonicalUrl.startsWith("https://cdn.example/vid/master.m3u8"))
                .findFirst()
                .orElseThrow();
        hlsRec.setQualities(List.of(720, 1080, 480));

        // дать таймеру Swing (700 мс) сработать минимум дважды
        // let the Swing timer (700 ms) fire at least twice
        Thread.sleep(1500);

        List<Component> all = new ArrayList<>();
        collect(panel, all);

        long rowCount = all.stream()
                .filter(c -> c.getClass().getName().endsWith("SourceRowPanel"))
                .count();
        List<String> texts = new ArrayList<>();
        for (Component c : all) {
            if (c instanceof JLabel) {
                String t = ((JLabel) c).getText();
                if (t != null && !t.isEmpty()) {
                    texts.add(t);
                }
            }
        }

        boolean qualitiesShown = texts.contains("Найдено / Found · качества / qualities: 1080p, 720p, 480p");
        boolean noAutoEnqueue = hlsRec.state() == SourceRecord.State.FOUND;

        System.out.println("rows=" + rowCount + " (expected 3: HLS + big MP4 + segment-as-MP4; small filtered)");
        System.out.println("labels=" + texts);
        System.out.println("QUALITIES_SHOWN=" + qualitiesShown);
        System.out.println("NO_AUTO_ENQUEUE=" + noAutoEnqueue);

        // имитируем клик «Скачать» на HLS-строке
        // simulate the user clicking "Скачать" on the HLS row
        JButton hlsButton = buttonInRowFor(panel, "https://cdn.example/vid/master.m3u8");
        System.out.println("DOWNLOAD_BUTTON=" + (hlsButton != null && hlsButton.isEnabled()));
        SwingUtilities.invokeLater(() -> hlsButton.doClick());
        Thread.sleep(300);

        // воркер работает против несуществующего CDN => состояние должно уйти из FOUND
        // (QUEUED/DOWNLOADING => позже ERROR из-за сбоя fetch)
        // worker runs against a nonexistent CDN => state must leave FOUND
        // (QUEUED/DOWNLOADING => later ERROR from the fetch failure)
        boolean enqueued = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            SourceRecord.State st = hlsRec.state();
            if (st != SourceRecord.State.FOUND) {
                enqueued = true;
                break;
            }
            Thread.sleep(200);
        }
        System.out.println("hlsStateAfterClick=" + hlsRec.state());
        System.out.println("BUTTON_ENQUEUE=" + enqueued);

        boolean ok = rowCount == 3
                && texts.contains("Test Video Title")
                && texts.stream().anyMatch(t -> t.startsWith("https://cdn.example/vid/master.m3u8"))
                && qualitiesShown
                && noAutoEnqueue
                && hlsButton != null && hlsButton.isEnabled()
                && enqueued;
        System.out.println(ok ? "UI_CHECK: PASS" : "UI_CHECK: FAIL");
        System.exit(ok ? 0 : 1);
    }

    /** Находит JButton действия в строке, чей ярлык URL начинается с заданного префикса. */
    /** Finds the action JButton inside the row whose URL label starts with the given prefix. */
    private static JButton buttonInRowFor(Component root, String urlPrefix) {
        List<Component> all = new ArrayList<>();
        collect(root, all);
        for (Component c : all) {
            if (c.getClass().getName().endsWith("SourceRowPanel")) {
                List<Component> row = new ArrayList<>();
                collect(c, row);
                JButton btn = null;
                boolean urlMatch = false;
                for (Component child : row) {
                    if (child instanceof JLabel) {
                        String t = ((JLabel) child).getText();
                        if (t != null && t.startsWith(urlPrefix)) {
                            urlMatch = true;
                        }
                    }
                    if (child instanceof JButton) {
                        btn = (JButton) child;
                    }
                }
                if (urlMatch && btn != null) {
                    return btn;
                }
            }
        }
        return null;
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof JComponent) {
            for (Component child : ((JComponent) c).getComponents()) {
                collect(child, out);
            }
        }
    }
}
