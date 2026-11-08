package com.flynet.hlsgrabber.hls;

import java.util.List;

/**
 * HLS media playlist (TS/fMP4 segments, AES-128 key, init map, byterange).
 */
public record MediaPlaylist(int mediaSequence, double targetDuration, List<Segment> segments,
                            KeyInfo playlistKey, String mapUri, boolean endListPresent) {

    /** TZ 5.2.1: live HLS lacks #EXT-X-ENDLIST. */
    public boolean isLive() {
        return !endListPresent;
    }

    public Segment segmentAt(int i) {
        return segments.get(i);
    }
}