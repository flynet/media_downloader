package com.flynet.hlsgrabber.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parser of a YouTube player response (inner-tube {@code /youtubei/v1/player} answer).
 *
 * <p>Real-site facts (Nov 2026, verified by harness):
 * <ul>
 *   <li>the WEB player response carries NO per-format URLs — YouTube serves media over
 *       server-side ABR (SABR) with UMP framing, so the generic network sniffer sees
 *       only POST /videoplayback (application/vnd.yt-ump) and never a manifest;</li>
 *   <li>the VISIONOS inner-tube client still returns CLASSIC signed stream URLs
 *       (plain {@code url}, no {@code n} throttle param) for every adaptive format,
 *       provided the request carries the page's {@code visitorData};</li>
 *   <li>those URLs answer a plain Range GET with 206 and the expected content range,
 *       so the download is an ordinary chunked HTTP GET + FFmpeg mux.</li>
 * </ul>
 *
 * <p>Signed URLs are kept in memory only and never logged; the log shows host+path.
 */
public final class YouTubePlayer {

    private static final Logger log = LoggerFactory.getLogger(YouTubePlayer.class);

    /** Parsed player response: what the downloader needs. */
    public record Player(String videoId,
                         String title,
                         String status,
                         boolean live,
                         long durationSeconds,
                         List<YouTubeFormat> formats) {

        /** true when the video is playable for this session. */
        public boolean ok() {
            return "OK".equals(status);
        }
    }

    private YouTubePlayer() {
    }

    /** Parses the raw JSON; never throws on malformed input (returns a "not playable" player). */
    public static Player parse(String json) {
        if (json == null || json.isBlank()) {
            return new Player(null, null, "EMPTY", false, 0, List.of());
        }
        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (!rootEl.isJsonObject()) {
                return new Player(null, null, "MALFORMED", false, 0, List.of());
            }
            JsonObject root = rootEl.getAsJsonObject();

            String videoId = optString(root, "videoDetails", "videoId");
            String title = optString(root, "videoDetails", "title");
            long duration = 0;
            JsonObject details = obj(root, "videoDetails");
            if (details != null && details.has("lengthSeconds")) {
                try {
                    duration = Long.parseLong(details.get("lengthSeconds").getAsString());
                } catch (NumberFormatException e) {
                    duration = 0;
                }
            }

            String status = "UNKNOWN";
            JsonObject play = obj(root, "playabilityStatus");
            if (play != null) {
                status = optString(play, "status");
                if (status == null) {
                    status = "UNKNOWN";
                }
            }

            boolean live = false;
            List<YouTubeFormat> formats = new ArrayList<>();
            JsonObject streaming = obj(root, "streamingData");
            if (streaming != null) {
                if (streaming.has("isLiveContent") && !streaming.get("isLiveContent").isJsonNull()) {
                    live = streaming.get("isLiveContent").getAsBoolean();
                }
                collect(formats, streaming.getAsJsonArray("adaptiveFormats"));
                collect(formats, streaming.getAsJsonArray("formats"));
            }
            return new Player(videoId, title, status, live, duration, List.copyOf(formats));
        } catch (Exception e) {
            log.debug("player response parse failed: {}", e.toString());
            return new Player(null, null, "PARSE_ERROR", false, 0, List.of());
        }
    }

    private static void collect(List<YouTubeFormat> out, JsonArray arr) {
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject f = el.getAsJsonObject();
            int itag = optInt(f, "itag");
            String mime = optString(f, "mimeType");
            String url = optString(f, "url");
            boolean cipherOnly = false;
            if ((url == null || url.isBlank()) && f.has("signatureCipher")) {
                // the player handed a decipherable cipher: the signature function from
                // base.js is not implemented (out of scope for stage 7)
                cipherOnly = true;
            }
            boolean hasVideo = mime != null && mime.toLowerCase().startsWith("video/");
            boolean hasAudio = mime != null && mime.toLowerCase().startsWith("audio/");
            out.add(new YouTubeFormat(
                    itag,
                    mime,
                    (url == null || url.isBlank()) ? null : url,
                    cipherOnly,
                    optInt(f, "width"),
                    optInt(f, "height"),
                    optInt(f, "fps"),
                    optLong(f, "bitrate"),
                    optLong(f, "contentLength"),
                    optString(f, "qualityLabel"),
                    optString(f, "audioQuality"),
                    optInt(f, "audioSampleRate"),
                    hasVideo,
                    hasAudio));
        }
    }

    /**
     * Video format pick: highest height not exceeding {@code cap} (0 = no cap); when
     * every format is above the cap, the overall highest is taken; equal heights are
     * broken by the higher bitrate, and MP4 (avc1) wins over WebM (vp9) so the result
     * can be muxed into an MP4 container without re-encoding.
     */
    public static YouTubeFormat pickVideo(Player player, int cap) {
        List<YouTubeFormat> candidates = new ArrayList<>();
        for (YouTubeFormat f : player.formats()) {
            if (!f.hasVideo() || f.url() == null || f.height() <= 0) {
                continue;
            }
            candidates.add(f);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        YouTubeFormat best = null;
        for (YouTubeFormat f : candidates) {
            if (cap > 0 && f.height() > cap) {
                continue;
            }
            if (betterVideo(f, best)) {
                best = f;
            }
        }
        if (best == null) {
            // cap below every available height: take the best available quality
            for (YouTubeFormat f : candidates) {
                if (betterVideo(f, best)) {
                    best = f;
                }
            }
        }
        return best;
    }

    private static boolean betterVideo(YouTubeFormat a, YouTubeFormat b) {
        if (b == null) {
            return true;
        }
        if (a.height() != b.height()) {
            return a.height() > b.height();
        }
        boolean aMp4 = "mp4".equals(a.container());
        boolean bMp4 = "mp4".equals(b.container());
        if (aMp4 != bMp4) {
            return aMp4;
        }
        return a.bitrate() > b.bitrate();
    }

    /**
     * Audio format pick: the highest-bitrate audio track; MP4 (m4a/aac) wins over
     * WebM (opus) so it matches the MP4 video container.
     */
    public static YouTubeFormat pickAudio(Player player) {
        YouTubeFormat best = null;
        for (YouTubeFormat f : player.formats()) {
            if (!f.hasAudio() || f.hasVideo() || f.url() == null) {
                continue;
            }
            if (best == null) {
                best = f;
                continue;
            }
            boolean aMp4 = "mp4".equals(f.container());
            boolean bMp4 = "mp4".equals(best.container());
            if (aMp4 != bMp4) {
                if (aMp4) {
                    best = f;
                }
                continue;
            }
            if (f.bitrate() > best.bitrate()) {
                best = f;
            }
        }
        return best;
    }

    /** Distinct heights of video formats, descending (the FOUND-row quality list). */
    public static List<Integer> qualities(Player player) {
        return player.formats().stream()
                .filter(f -> f.hasVideo() && f.url() != null && f.height() > 0)
                .map(YouTubeFormat::height)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /** Human-readable reason for a non-OK playability status (for the UI). */
    public static String reasonOf(Player player) {
        if (player.status() == null) {
            return "no playability data";
        }
        return switch (player.status()) {
            case "OK" -> "";
            case "LOGIN_REQUIRED" -> "YouTube requires sign-in";
            case "UNPLAYABLE" -> "video unavailable for playback";
            case "ERROR" -> "YouTube: playback error";
            case "EMPTY" -> "empty player response";
            case "MALFORMED", "PARSE_ERROR" -> "failed to parse the player response";
            default -> "YouTube: " + player.status();
        };
    }

    // ---------- tiny JSON helpers (Gson) ----------

    private static JsonObject obj(JsonObject root, String name) {
        if (root == null || !root.has(name) || root.get(name).isJsonNull()) {
            return null;
        }
        JsonElement el = root.get(name);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String optString(JsonObject root, String... path) {
        JsonObject cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            cur = obj(cur, path[i]);
            if (cur == null) {
                return null;
            }
        }
        return optString(cur, path[path.length - 1]);
    }

    private static String optString(JsonObject o, String name) {
        if (o == null || !o.has(name) || o.get(name).isJsonNull()) {
            return null;
        }
        try {
            return o.get(name).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int optInt(JsonObject o, String name) {
        if (o == null || !o.has(name) || o.get(name).isJsonNull()) {
            return 0;
        }
        try {
            return o.get(name).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long optLong(JsonObject o, String name) {
        if (o == null || !o.has(name) || o.get(name).isJsonNull()) {
            return 0;
        }
        try {
            return o.get(name).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }
}
