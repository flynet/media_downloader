package com.example.hlsgrabber.youtube;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор youtube-формата (TZ 5.2.3 + требование пользователя): максимальное качество,
 * не превышающее лимит, при равенстве — по битрейту, MP4 предпочтительнее WebM для
 * склейки.
 *
 * YouTube format selection (TZ 5.2.3 + user requirement): the highest quality not
 * exceeding the cap, ties broken by bitrate, MP4 preferred over WebM for muxing.
 */
class YouTubePlayerTest {

    private static final String JSON = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"videoId": "hK6A_ebUKrM", "title": "Test", "lengthSeconds": "562"},
              "streamingData": {
                "adaptiveFormats": [
                  {"itag": 999, "mimeType": "video/mp4; codecs=\\"avc1\\"", "width": 7680, "height": 4320,
                   "signatureCipher": "url=https%3A%2F%2Fx%2Fv%3Fid%3D999"},
                  {"itag": 315, "mimeType": "video/webm; codecs=\\"vp9\\"", "width": 3840, "height": 2160,
                   "fps": 30, "bitrate": 12000000, "contentLength": "800000000", "url": "https://x/v?id=315"},
                  {"itag": 299, "mimeType": "video/mp4; codecs=\\"avc1.64002A\\"", "width": 1920, "height": 1080,
                   "fps": 60, "bitrate": 5804061, "contentLength": "395052331", "url": "https://x/v?id=299"},
                  {"itag": 303, "mimeType": "video/webm; codecs=\\"vp9\\"", "width": 1920, "height": 1080,
                   "fps": 60, "bitrate": 7664593, "contentLength": "251534879", "url": "https://x/v?id=303"},
                  {"itag": 298, "mimeType": "video/mp4; codecs=\\"avc1.4D4020\\"", "width": 1280, "height": 720,
                   "fps": 60, "bitrate": 3484930, "contentLength": "227935505", "url": "https://x/v?id=298"},
                  {"itag": 135, "mimeType": "video/mp4; codecs=\\"avc1.4D401F\\"", "width": 854, "height": 480,
                   "fps": 30, "bitrate": 1160477, "contentLength": "52487413", "url": "https://x/v?id=135"},
                  {"itag": 140, "mimeType": "audio/mp4; codecs=\\"mp4a.40.2\\"", "bitrate": 130996,
                   "contentLength": "9093819", "audioQuality": "AUDIO_QUALITY_MEDIUM", "url": "https://x/a?id=140"},
                  {"itag": 251, "mimeType": "audio/webm; codecs=\\"opus\\"", "bitrate": 151061,
                   "contentLength": "9321142", "url": "https://x/a?id=251"}
                ]
              }
            }
            """;

    @Test
    void parsesPlayerResponse() {
        YouTubePlayer.Player p = YouTubePlayer.parse(JSON);
        assertTrue(p.ok());
        assertEquals("hK6A_ebUKrM", p.videoId());
        assertEquals("Test", p.title());
        assertEquals(562, p.durationSeconds());
        assertEquals(8, p.formats().size());
    }

    @Test
    void qualitiesAreDescendingDistinct() {
        YouTubePlayer.Player p = YouTubePlayer.parse(JSON);
        assertEquals(java.util.List.of(2160, 1080, 720, 480), YouTubePlayer.qualities(p));
    }

    @Test
    void capPrefersHighestNotAboveCap() {
        YouTubePlayer.Player p = YouTubePlayer.parse(JSON);
        YouTubeFormat v = YouTubePlayer.pickVideo(p, 1080);
        assertNotNull(v);
        assertEquals(1080, v.height());
        // MP4 побеждает WebM с бо́льшим битрейтом при той же высоте (muxable into MP4)
        // MP4 wins over the higher-bitrate WebM at the same height (muxable into MP4)
        assertEquals(299, v.itag());
        assertEquals("mp4", v.container());
    }

    @Test
    void selected720Picks720Format() {
        YouTubeFormat v = YouTubePlayer.pickVideo(YouTubePlayer.parse(JSON), 720);
        assertNotNull(v);
        assertEquals(720, v.height());
        assertEquals(298, v.itag());
    }

    @Test
    void capBelowAllFallsBackToOverallBest() {
        YouTubePlayer.Player p = YouTubePlayer.parse(JSON);
        // все доступные высоты выше лимита -> берётся лучший из всех
        // (требование пользователя: лимит ниже всех -> общий максимум)
        // every available height is above the cap -> the overall best is taken
        // (user requirement: cap below all -> the common maximum)
        YouTubeFormat v = YouTubePlayer.pickVideo(p, 360);
        assertNotNull(v);
        assertEquals(2160, v.height());
        assertEquals(315, v.itag());
    }

    @Test
    void noCapPicksBestAvailableResolution() {
        YouTubeFormat v = YouTubePlayer.pickVideo(YouTubePlayer.parse(JSON), 0);
        assertNotNull(v);
        assertEquals(2160, v.height());
        assertEquals(315, v.itag());
    }

    @Test
    void audioPrefersMp4Container() {
        YouTubePlayer.Player p = YouTubePlayer.parse(JSON);
        YouTubeFormat a = YouTubePlayer.pickAudio(p);
        assertNotNull(a);
        assertEquals(140, a.itag());
        assertEquals("mp4", a.container());
    }

    @Test
    void malformedInputIsSafe() {
        assertNull(YouTubePlayer.pickVideo(YouTubePlayer.parse("not json"), 1080));
        assertEquals("EMPTY", YouTubePlayer.parse(null).status());
        assertEquals("PARSE_ERROR", YouTubePlayer.parse("{broken").status());
    }

    @Test
    void liveStreamIsFlagged() {
        String live = JSON.replace("\"adaptiveFormats\": [",
                "\"isLiveContent\": true, \"adaptiveFormats\": [");
        assertTrue(YouTubePlayer.parse(live).live());
    }
}
