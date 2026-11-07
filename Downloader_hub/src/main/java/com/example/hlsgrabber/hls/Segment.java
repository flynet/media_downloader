package com.example.hlsgrabber.hls;

/**
 * Один медиальный сегмент медиального плейлиста / One media segment of a media playlist.
 *
 * @param sequence номер media sequence (mediaSequence + позиция) / media sequence number (mediaSequence + position)
 * @param duration секунд из #EXTINF / seconds from #EXTINF
 * @param rawUri    URI сегмента как записан в плейлисте / segment URI as written in playlist
 * @param byteRange сырой #EXT-X-BYTERANGE для сегмента ("len" или "len@offset"), null если нет / raw #EXT-X-BYTERANGE for this segment ("len" or "len@offset"), null if none
 */
public record Segment(int sequence, double duration, String rawUri, String byteRange) {
}