package com.example.hlsgrabber.hls;

/**
 * Строка #EXT-X-KEY плейлиста HLS (TZ 6.2) / HLS #EXT-X-KEY line (TZ 6.2).
 *
 * @param method метод: NONE, AES-128, SAMPLE-AES, ... / method: NONE, AES-128, SAMPLE-AES, ...
 * @param uri    URI как записан в плейлисте (может быть относительным), null при NONE / raw URI as written in playlist (may be relative), null for NONE
 * @param ivHex  IV как записан (0x...), null если отсутствует (выводится из media sequence) / raw IV as written (0x...), null when absent (derive from media sequence)
 */
public record KeyInfo(String method, String uri, String ivHex) {

    public boolean isAes128() {
        return "AES-128".equals(method);
    }

    /** Признак DRM по TZ 5.2.2: любой метод кроме NONE / AES-128 / DRM marker per TZ 5.2.2: any method other than NONE / AES-128. */
    public boolean isDrm() {
        return method != null && !method.equals("NONE") && !method.equals("AES-128");
    }
}