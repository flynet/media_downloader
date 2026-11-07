package com.flynet.hlsgrabber;

import com.flynet.hlsgrabber.util.UrlUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Тесты UrlUtils (TZ 5.2.4, 6.1): разрешение относительных, наследование query, канонизация.
 *  UrlUtils tests (TZ 5.2.4, 6.1): relative resolution, query inheritance, canonicalization. */
class UrlUtilsTest {

    @Test
    void resolvesRelativeSegmentFromPlaylistUrl() {
        String base = "http://cdn.example.com/videos/show/index.m3u8";
        assertEquals("http://cdn.example.com/videos/show/seg1.ts",
                UrlUtils.resolve(base, "seg1.ts"));
    }

    @Test
    void resolvesPathRelative() {
        String base = "http://cdn.example.com/videos/show/index.m3u8";
        assertEquals("http://cdn.example.com/audio.m3u8",
                UrlUtils.resolve(base, "/audio.m3u8"));
    }

    @Test
    void keepsAbsoluteUrls() {
        assertEquals("https://other.host/a/b.ts",
                UrlUtils.resolve("http://cdn.example.com/x/index.m3u8", "https://other.host/a/b.ts"));
    }

    @Test
    void inheritsBaseQueryWhenRefHasNone() {
        String base = "http://cdn.example.com/show/index.m3u8?token=abc&sig=xyz";
        assertEquals("http://cdn.example.com/show/seg1.ts?token=abc&sig=xyz",
                UrlUtils.resolve(base, "seg1.ts"));
    }

    @Test
    void refOwnQueryWins() {
        String base = "http://cdn.example.com/show/index.m3u8?token=abc";
        assertEquals("http://cdn.example.com/show/seg1.ts?tok=own",
                UrlUtils.resolve(base, "seg1.ts?tok=own"));
    }

    @Test
    void canonicalizesVolatileTokens() {
        String a = UrlUtils.canonicalize("http://cdn.example.com/show/index.m3u8?token=T1&lang=ru");
        String b = UrlUtils.canonicalize("http://cdn.example.com/show/index.m3u8?token=T2&lang=ru");
        String c = UrlUtils.canonicalize("http://cdn.example.com/show/index.m3u8?lang=ru#frag");
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals("http://cdn.example.com/show/index.m3u8?lang=ru", a);
    }

    @Test
    void canonicalizeKeepsStableParams() {
        String u = UrlUtils.canonicalize("http://h/p.mp4?size=2&exp=999");
        assertEquals("http://h/p.mp4?size=2", u);
    }

    @Test
    void detectsManifestCandidates() {
        assertTrue(UrlUtils.isManifestCandidate("http://h/a.m3u8?x=1", null));
        assertTrue(UrlUtils.isManifestCandidate("http://h/a.mpd", null));
        assertTrue(UrlUtils.isManifestCandidate("http://h/a", "application/vnd.apple.mpegurl"));
        assertTrue(UrlUtils.isManifestCandidate("http://h/a", "application/x-mpegURL"));
        assertTrue(UrlUtils.isManifestCandidate("http://h/a", "audio/mpegurl"));
        assertTrue(UrlUtils.isManifestCandidate("http://h/a", "application/dash+xml"));
        assertFalse(UrlUtils.isManifestCandidate("http://h/a.ts", null));
        assertFalse(UrlUtils.isManifestCandidate("http://h/a", "application/json"));
    }

    @Test
    void detectsDirectMp4() {
        assertTrue(UrlUtils.isDirectMp4Candidate("http://h/v.mp4?token=x", null));
        assertTrue(UrlUtils.isDirectMp4Candidate("http://h/v", "video/mp4"));
        assertFalse(UrlUtils.isDirectMp4Candidate("http://h/v.m4s", null));
        assertFalse(UrlUtils.isDirectMp4Candidate("http://h/v.m3u8", null));
    }

    @Test
    void pathOfStripsQueryAndFragment() {
        assertEquals("/videos/a.mp4", UrlUtils.pathOf("http://h/videos/a.mp4?token=t#f"));
    }

    @Test
    void resolvePreservesSingleEncodedSignature() {
        // реальный сбой phncdn (приёмка GUI): плейлист несёт подписные URL на каждый
        // файл h=...%2B...%3D; resolve() не должен пере-кодировать их (%252B -> HTTP 500)
        // phncdn real-site failure (GUI acceptance): the playlist carries per-file
        // signed URLs h=...%2B...%3D; resolve() must not re-encode them (%252B -> HTTP 500)
        String base = "https://hv-h.phncdn.com/hls/c6541/videos/202611/04/67955105/"
                + "1080P_4000K_67955105.mp4/playlist.m3u8?h=SigA%2BB%3D&e=1793983232&f=1";
        String ref = "seg-6-v1-a1.m4s?h=1mC2O8Mz1fbLZulBdtU%2BKlpN8gw%3D&e=1793983232&f=1";
        String expected = "https://hv-h.phncdn.com/hls/c6541/videos/202611/04/67955105/"
                + "1080P_4000K_67955105.mp4/seg-6-v1-a1.m4s?h=1mC2O8Mz1fbLZulBdtU%2BKlpN8gw%3D&e=1793983232&f=1";
        assertEquals(expected, UrlUtils.resolve(base, ref));
    }

    @Test
    void canonicalizePreservesRawEncoding() {
        assertEquals("http://h/p.mp4?size=a%2Bb%3D",
                UrlUtils.canonicalize("http://h/p.mp4?size=a%2Bb%3D&exp=999"));
    }
}