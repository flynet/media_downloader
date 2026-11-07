package com.example.hlsgrabber.download;

import com.example.hlsgrabber.hls.HlsParser;
import com.example.hlsgrabber.hls.MasterPlaylist;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Max-quality selection tests (user requirement): the download picks the highest
 * variant not exceeding the cap (targetHeight, 0 = no cap); when every variant is
 * above the cap, it falls back to the overall highest; ties break on bandwidth.
 */
class SegmentDownloaderTest {

    private static final String MASTER = """
            #EXTM3U
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
        assertEquals(1080, best.height());
        assertEquals("high.m3u8", best.rawUri());
    }

    @Test
    void capZeroMeansNoCap() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = downloader().pickVariant(m, 0);
        assertEquals(1080, best.height());
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
}
