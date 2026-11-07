package com.flynet.hlsgrabber.hls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Парсит HLS-плейлисты (TZ 6.2): мастер-плейлисты (#EXT-X-STREAM-INF, #EXT-X-MEDIA)
 * и медиальные (#EXTINF, #EXT-X-KEY, #EXT-X-MAP, #EXT-X-BYTERANGE,
 * #EXT-X-MEDIA-SEQUENCE, #EXT-X-ENDLIST) /
 * Parses HLS playlists (TZ 6.2): master (#EXT-X-STREAM-INF, #EXT-X-MEDIA) and
 * media (#EXTINF, #EXT-X-KEY, #EXT-X-MAP, #EXT-X-BYTERANGE, #EXT-X-MEDIA-SEQUENCE,
 * #EXT-X-ENDLIST).
 */
public final class HlsParser {

    private HlsParser() {
    }

    public static boolean isMaster(String text) {
        return text.contains("#EXT-X-STREAM-INF:");
    }

    /** Парсинг мастер-плейлиста / Parse master playlist. */
    public static MasterPlaylist parseMaster(String text) {
        List<MasterPlaylist.Variant> variants = new ArrayList<>();
        List<MasterPlaylist.MediaEntry> media = new ArrayList<>();
        String pendingUri = null;

        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attrs = parseAttrs(line.substring("#EXT-X-STREAM-INF:".length()));
                pendingUri = null;
                variants.addLast(variant(attrs));
                pendingUri = "SET";
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attrs = parseAttrs(line.substring("#EXT-X-MEDIA:".length()));
                media.add(new MasterPlaylist.MediaEntry(
                        attrs.get("TYPE"), attrs.get("GROUP-ID"), attrs.get("NAME"),
                        attrs.get("URI"), "YES".equals(attrs.get("DEFAULT")), attrs.get("LANGUAGE")));
            } else if (line.equals("#EXT-X-STREAM-INF-END")) {
                // не реальный тег — игнорируем / not a real tag; ignore
            } else if (!line.isEmpty() && !line.startsWith("#") && pendingUri != null) {
                variants.set(variants.size() - 1, withUri(variants.get(variants.size() - 1), line));
                pendingUri = null;
            }
        }
        return new MasterPlaylist(variants, media);
    }

    private static MasterPlaylist.Variant variant(Map<String, String> attrs) {
        long bandwidth = parseLong(attrs.get("BANDWIDTH"));
        Integer height = null;
        Integer width = null;
        String res = attrs.get("RESOLUTION");
        if (res != null && res.contains("x")) {
            String[] wx = res.split("x");
            width = parseInt(wx[0]);
            height = parseInt(wx[1]);
        }
        return new MasterPlaylist.Variant(null, bandwidth, height, width,
                attrs.get("AUDIO"), attrs.get("CODECS"), attrs.get("NAME"));
    }

    private static MasterPlaylist.Variant withUri(MasterPlaylist.Variant v, String uri) {
        return new MasterPlaylist.Variant(uri, v.bandwidth(), v.height(), v.width(),
                v.audioGroup(), v.codecs(), v.name());
    }

    /** Парсинг медиального плейлиста / Parse media playlist. */
    public static MediaPlaylist parseMedia(String text) {
        int mediaSequence = 0;
        double targetDuration = 0;
        List<Segment> segments = new ArrayList<>();
        KeyInfo playlistKey = null;   // текущее состояние ключа / current key state
        String mapUri = null;
        boolean endList = false;

        String pendingRange = null;
        double pendingDuration = 0;

        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseInt(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()));
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = parseDouble(line.substring("#EXT-X-TARGETDURATION:".length()));
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attrs = parseAttrs(line.substring("#EXT-X-KEY:".length()));
                String method = attrs.get("METHOD");
                if ("NONE".equals(method)) {
                    playlistKey = null;
                } else {
                    playlistKey = new KeyInfo(method, attrs.get("URI"), attrs.get("IV"));
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attrs = parseAttrs(line.substring("#EXT-X-MAP:".length()));
                mapUri = attrs.get("URI");
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = line.substring("#EXT-X-BYTERANGE:".length());
            } else if (line.startsWith("#EXTINF:")) {
                String val = line.substring("#EXTINF:".length());
                int comma = val.indexOf(',');
                pendingDuration = parseDouble(comma >= 0 ? val.substring(0, comma) : val);
            } else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                int sequence = mediaSequence + segments.size();
                segments.add(new Segment(sequence, pendingDuration, line, pendingRange));
                pendingRange = null;
                pendingDuration = 0;
            }
        }
        return new MediaPlaylist(mediaSequence, targetDuration, segments, playlistKey, mapUri, endList);
    }

    /** Парсит список атрибутов с учётом значений в кавычках (напр. CODECS="a,b",RESOLUTION=1920x1080) / Parse attribute list honoring quoted values (e.g. CODECS="a,b",RESOLUTION=1920x1080). */
    static Map<String, String> parseAttrs(String attrLine) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        int n = attrLine.length();
        while (i < n) {
            int eq = attrLine.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String key = attrLine.substring(i, eq).trim();
            int v = eq + 1;
            String value;
            if (v < n && attrLine.charAt(v) == '"') {
                int close = v + 1;
                while (close < n && attrLine.charAt(close) != '"') {
                    close++;
                }
                value = attrLine.substring(v + 1, Math.min(close, n));
                i = close + 1;
                if (i < n && attrLine.charAt(i) == ',') {
                    i++;
                }
            } else {
                int comma = attrLine.indexOf(',', v);
                int end = comma < 0 ? n : comma;
                value = attrLine.substring(v, end).trim();
                i = end + 1;
            }
            map.put(key, value);
        }
        return map;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}