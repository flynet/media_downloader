package com.flynet.hlsgrabber.youtube;

/**
 * One adaptive/progressive format from ytInitialPlayerResponse.streamingData.
 *
 * <p>Stream URLs carry the player signature ({@code sig}/{@code s}) and sometimes the
 * {@code n} throttle descriptor — they must never be printed: only host+path is logged.
 */
public record YouTubeFormat(int itag,
                            String mimeType,
                            /** plain, ready-to-fetch stream URL (null when the player handed a cipher only) */
                            String url,
                            /** true when the player response carried only a signatureCipher for this itag */
                            boolean cipherOnly,
                            int width,
                            int height,
                            int fps,
                            long bitrate,
                            long contentLength,
                            String qualityLabel,
                            String audioQuality,
                            int audioSampleRate,
                            boolean hasVideo,
                            boolean hasAudio) {

    /** container family: "mp4" or "webm" — video and audio
     *  must share it for -c copy muxing */
    public String container() {
        if (mimeType == null) {
            return "";
        }
        String m = mimeType.toLowerCase();
        if (m.contains("webm")) {
            return "webm";
        }
        if (m.contains("mp4")) {
            return "mp4";
        }
        return m.contains("ogg") ? "ogg" : "other";
    }
}
