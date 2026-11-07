package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.hls.HlsParser;
import com.flynet.hlsgrabber.hls.KeyInfo;
import com.flynet.hlsgrabber.hls.MasterPlaylist;
import com.flynet.hlsgrabber.hls.MediaPlaylist;
import com.flynet.hlsgrabber.hls.Segment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Тесты HLS-парсера (TZ раздел 9): мастер, медиа, AES-128, EXT-X-MAP, byterange, live. */
/** HLS parser tests (TZ section 9): master, media, AES-128, EXT-X-MAP, byterange, live. */
class HlsParserTest {

    private static final String MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1024000,RESOLUTION=1920x1080,CODECS="avc1.64001f,mp4a.40.2",AUDIO="aa"
            1080p.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=400000,RESOLUTION=1280x720
            720p.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=200000,RESOLUTION=640x360
            360p.m3u8
            #EXT-X-MEDIA:TYPE=AUDIO,URI="audio_en.m3u8",GROUP-ID="aa",LANGUAGE="eng",NAME="English",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"
            #EXT-X-MEDIA:TYPE=AUDIO,URI="audio_ru.m3u8",GROUP-ID="aa",LANGUAGE="rus",NAME="Russian",AUTOSELECT=YES
            #EXT-X-ENDLIST
            """;

    @Test
    void parsesMasterVariants() {
        assertTrue(HlsParser.isMaster(MASTER));
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        assertEquals(3, m.variants().size());
        MasterPlaylist.Variant v = m.variants().get(0);
        assertEquals("1080p.m3u8", v.rawUri());
        assertEquals(1080, v.height());
        assertEquals(1024000, v.bandwidth());
        assertEquals("aa", v.audioGroup());
        assertEquals("avc1.64001f,mp4a.40.2", v.codecs());
        assertEquals(3, m.variants().stream().filter(x -> x.rawUri() != null).count());
    }

    @Test
    void picksHighestBandwidth1080pAndDefaultAudio() {
        MasterPlaylist m = HlsParser.parseMaster(MASTER);
        MasterPlaylist.Variant best = m.variantsWithHeight(1080).get(0);
        assertEquals(1080, best.height());
        assertEquals(1024000, best.bandwidth());
        MasterPlaylist.MediaEntry a = m.bestAudio("aa");
        assertNotNull(a);
        assertEquals("audio_en.m3u8", a.rawUri());
        assertTrue(a.isDefault());
    }

    @Test
    void no1080pVariantsMeansEmptySelection() {
        String master = MASTER.replaceAll("RESOLUTION=1920x1080", "RESOLUTION=1921x1081");
        MasterPlaylist m = HlsParser.parseMaster(master);
        assertTrue(m.variantsWithHeight(1080).isEmpty());
    }

    private static final String MEDIA_AES = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:11
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000000
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg11.ts
            #EXTINF:4.0,
            seg12.ts
            #EXTINF:2.5,
            seg13.ts
            #EXT-X-ENDLIST
            """;

    @Test
    void parsesMediaWithKeyMapAndSequence() {
        MediaPlaylist p = HlsParser.parseMedia(MEDIA_AES);
        assertFalse(p.isLive());
        assertEquals(11, p.mediaSequence());
        assertEquals(3, p.segments().size());
        KeyInfo k = p.playlistKey();
        assertEquals("AES-128", k.method());
        assertEquals("key.bin", k.uri());
        assertEquals("0x00000000000000000000000000000000", k.ivHex());
        assertEquals("init.mp4", p.mapUri());
        Segment s0 = p.segmentAt(0);
        assertEquals(11, s0.sequence());
        assertEquals("seg11.ts", s0.rawUri());
        assertEquals(4.0, s0.duration(), 1e-9);
        assertNull(s0.byteRange());
    }

    @Test
    void byterangeAppliesToNextSegment() {
        String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:6.0,
                #EXT-X-BYTERANGE:72847@0
                big.mp4
                #EXTINF:6.0,
                #EXT-X-BYTERANGE:72847@72847
                big.mp4
                #EXT-X-ENDLIST
                """;
        MediaPlaylist p = HlsParser.parseMedia(media);
        assertEquals("72847@0", p.segmentAt(0).byteRange());
        assertEquals("72847@72847", p.segmentAt(1).byteRange());
        assertEquals("big.mp4", p.segmentAt(0).rawUri());
        assertEquals(0, p.segmentAt(0).sequence());
        assertEquals(1, p.segmentAt(1).sequence());
    }

    @Test
    void livePlaylistDetectedByMissingEndlist() {
        String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:4
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:4.0,
                seg0.ts
                """;
        MediaPlaylist p = HlsParser.parseMedia(media);
        assertTrue(p.isLive());
    }

    @Test
    void keyNoneResetsKeyState() {
        String media = """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:4.0,
                seg0.ts
                #EXT-X-KEY:METHOD=NONE
                #EXTINF:4.0,
                seg1.ts
                #EXT-X-ENDLIST
                """;
        MediaPlaylist p = HlsParser.parseMedia(media);
        // KEY:METHOD=NONE сбрасывает состояние ключа до «нет ключа»
        // KEY:METHOD=NONE resets the key state to "no key"
        assertNull(p.playlistKey());
    }

    @Test
    void drmKeyMethodFlagged() {
        KeyInfo k = new KeyInfo("SAMPLE-AES", "k.bin", null);
        assertTrue(k.isDrm());
        KeyInfo a = new KeyInfo("AES-128", "k.bin", null);
        assertFalse(a.isDrm());
        KeyInfo n = new KeyInfo("NONE", null, null);
        assertFalse(n.isDrm());
    }
}