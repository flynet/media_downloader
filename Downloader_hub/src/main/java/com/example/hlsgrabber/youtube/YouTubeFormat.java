package com.example.hlsgrabber.youtube;

/**
 * Один адаптивный/прогрессивный формат из ytInitialPlayerResponse.streamingData.
 * One adaptive/progressive format from ytInitialPlayerResponse.streamingData.
 *
 * <p>URL потоков несут подпись плеера ({@code sig}/{@code s}) и иногда
 * дескриптер дросселирования {@code n} — их нельзя печатать: логируется только host+path.
 * Stream URLs carry the player signature ({@code sig}/{@code s}) and sometimes the
 * {@code n} throttle descriptor — they must never be printed: only host+path is logged.
 */
public record YouTubeFormat(int itag,
                            String mimeType,
                            /** готовый к загрузке URL потока (null, когда плеер дал только cipher)
                             *  plain, ready-to-fetch stream URL (null when the player handed a cipher only) */
                            String url,
                            /** true, когда player response содержал только signatureCipher для этого itag
                             *  true when the player response carried only a signatureCipher for this itag */
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

    /** семейство контейнера: «mp4» или «webm» — видео и аудио должны делить его
     *  для muxing с -c copy / container family: "mp4" or "webm" — video and audio
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
