package com.flynet.hlsgrabber.download;

import com.flynet.hlsgrabber.hls.HlsParser;
import com.flynet.hlsgrabber.hls.MasterPlaylist;
import com.flynet.hlsgrabber.hls.MediaPlaylist;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты выбора максимального качества (требование пользователя): загрузка берёт
 * наивысший вариант, не превышающий лимит (targetHeight, 0 = без лимита); когда все
 * варианты выше лимита — откат к общему максимуму; ничья разрешается по bandwidth.
 * Max-quality selection tests (user requirement): the download picks the highest
 * variant not exceeding the cap (targetHeight, 0 = no cap); when every variant is
 * above the cap, it falls back to the overall highest; ties break on bandwidth.
 */
class SegmentDownloaderTest {

    private static final String MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160
            ultra.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            mid.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            high.m3u8
            """;

    private static final String MASTER_TIE = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            mid_a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            mid_b.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            high.m3u8
            """;

    private static SegmentDownloader downloader() {
        return new SegmentDownloader(null, null, 1);
    }

    @Test
    void cap1080PicksTheHighest() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 1080);
        assertEquals(1080, best.height());
        assertEquals("high.m3u8", best.rawUri());
    }

    @Test
    void cap720PicksMaxWithinCap() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 720);
        assertEquals(720, best.height());
        assertEquals("mid.m3u8", best.rawUri());
    }

    @Test
    void capBelowAllVariantsFallsBackToOverallMax() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 360);
        assertEquals(2160, best.height());
        assertEquals("ultra.m3u8", best.rawUri());
    }

    @Test
    void capZeroMeansNoCap() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 0);
        assertEquals(2160, best.height());
        assertEquals("ultra.m3u8", best.rawUri());
    }

    @Test
    void cap2160PicksUltraHighDefinition() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 2160);
        assertEquals(2160, best.height());
        assertEquals("ultra.m3u8", best.rawUri());
    }

    @Test
    void sameHeightTieBreaksOnBandwidth() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER_TIE);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 720);
        assertEquals(720, best.height());
        assertEquals("mid_b.m3u8", best.rawUri());
    }

    @Test
    void masterWithoutHeightsReturnsNull() {
        String master = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000
                nodata.m3u8
                """;
        MasterPlaylist m = HlsParser.parseMaster(master);
        assertNull(downloader().pickVariant(m, 1080));
        assertNull(downloader().pickVariant(m, 0));
    }

    @Test
    void segmentExtStripsSignedQueryFromM4s() {
        String ext = SegmentDownloader.segmentExt(
                "seg-6-v1-a1.m4s?h=1mC2O8Mz1fbLZulBdtU%252BKlpN8gw%253D&e=1793983232&f=1", ".ts");
        assertEquals(".m4s", ext);
    }

    @Test
    void segmentExtDefaultsForUsablePaths() {
        assertEquals(".ts", SegmentDownloader.segmentExt("seg-1-v1-a1.ts?h=sig", ".ts"));
        assertEquals(".mp4", SegmentDownloader.segmentExt("init.mp4?h=sig", ".mp4"));
        assertEquals(".ts", SegmentDownloader.segmentExt("segment", ".ts"));
        assertEquals(".ts", SegmentDownloader.segmentExt("dir.x/y", ".ts"));
        assertEquals(".ts", SegmentDownloader.segmentExt("dir/x.y.z.toolong", ".ts"));
        assertEquals(".mp4", SegmentDownloader.segmentExt("/path/to/init?h=x", ".mp4"));
    }

    /**
     * Локальный плейлист задаёт HLS-демузер, который переставляет сегменты на
     * таймлайн плейлиста. Плейлист должен нести оригинальные длительности EXTINF
     * (неверная длительность возвращает скачки) и ссылки на скачанные файлы по имени.
     * The local playlist drives the HLS demuxer, which re-bases the segments onto the
     * playlist timeline. It must carry the original EXTINF durations (a wrong
     * duration reintroduces the stutter) and reference the downloaded files by name.
     */
    @Test
    void localPlaylistCarriesExtinfDurationsAndSegmentNames() {
        String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:7
                #EXTINF:6.000,
                seg-7.ts
                #EXTINF:4.500,
                seg-8.ts
                #EXT-X-ENDLIST
                """;
        MediaPlaylist mp = HlsParser.parseMedia(media);
        List<Path> files = List.of(Path.of("seg000007.ts"), Path.of("seg000008.ts"));
        String text = SegmentDownloader.localPlaylistText(mp, files);

        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:7"));
        assertTrue(text.contains("#EXT-X-TARGETDURATION:6"));
        assertTrue(text.contains("#EXTINF:6.000,"));
        assertTrue(text.contains("#EXTINF:4.500,"));
        assertTrue(text.contains("seg000007.ts"));
        assertTrue(text.contains("seg000008.ts"));
        assertTrue(text.endsWith("#EXT-X-ENDLIST\n"));
        // каждый сегмент должен предваряться своим EXTINF, в порядке плейлиста
        // every segment must be preceded by its EXTINF, in playlist order
        assertTrue(text.indexOf("seg000007.ts") < text.indexOf("seg000008.ts"));
    }

    @Test
    void localPlaylistTargetDurationCoversTheLongestSegment() {
        String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:9.750,
                a.ts
                #EXT-X-ENDLIST
                """;
        MediaPlaylist mp = HlsParser.parseMedia(media);
        String text = SegmentDownloader.localPlaylistText(mp, List.of(Path.of("a.ts")));
        assertTrue(text.contains("#EXT-X-TARGETDURATION:10"));
        assertTrue(text.contains("#EXTINF:9.750,"));
    }

    /** Ожидаемая длительность потока = сумма значений EXTINF плейлиста.
     * The expected length of the stream is the sum of the playlist EXTINF values. */
    @Test
    void expectedDurationSumsExtinf() {
        String media = """
                #EXTM3U
                #EXTINF:6.000,
                a.ts
                #EXTINF:4.500,
                b.ts
                #EXT-X-ENDLIST
                """;
        MediaPlaylist mp = HlsParser.parseMedia(media);
        assertEquals(10.5, SegmentDownloader.expectedDuration(mp), 1e-6);
    }

    /**
     * Более короткая склейка означает потерянные сегменты (HLS-демузер открывает
     * каждый сегмент отдельно) — это должно запустить байтовый фолбэк.
     * A shorter assembled stream means segments were dropped (the HLS demuxer opens
     * every segment separately) — that must trigger the byte-join fallback.
     */
    @Test
    void shortAssemblyIsTreatedAsLoss() {
        assertFalse(SegmentDownloader.looksComplete(100.0, 40.0));
        assertFalse(SegmentDownloader.looksComplete(100.0, 97.0));
        assertTrue(SegmentDownloader.looksComplete(100.0, 100.0));
        assertTrue(SegmentDownloader.looksComplete(100.0, 103.0));
        // источник с собственными дырами — не наше дело
        // a source that carries its own holes is not our business
        assertTrue(SegmentDownloader.looksComplete(100.0, 112.0));
        // нет данных -> нет вердикта, фолбэк не запускаем
        // no data -> no verdict, do not trigger a fallback
        assertTrue(SegmentDownloader.looksComplete(0.0, 10.0));
        assertTrue(SegmentDownloader.looksComplete(100.0, -1.0));
    }
}
