package com.flynet.hlsgrabber.hls;

/**
 * HLS #EXT-X-KEY line (TZ 6.2).
 *
 * @param method method: NONE, AES-128, SAMPLE-AES, ...
 * @param uri    raw URI as written in playlist (may be relative), null for NONE
 * @param ivHex  raw IV as written (0x...), null when absent (derive from media sequence)
 */
public record KeyInfo(String method, String uri, String ivHex) {

    public boolean isAes128() {
        return "AES-128".equals(method);
    }

    /** DRM marker per TZ 5.2.2: any method other than NONE / AES-128. */
    public boolean isDrm() {
        return method != null && !method.equals("NONE") && !method.equals("AES-128");
    }
}