package com.example.hlsgrabber.hls;

/**
 * One media segment of a media playlist.
 *
 * @param sequence  media sequence number (mediaSequence + position)
 * @param duration  seconds from #EXTINF
 * @param rawUri    segment URI as written in playlist
 * @param byteRange raw #EXT-X-BYTERANGE for this segment ("len" or "len@offset"), null if none
 */
public record Segment(int sequence, double duration, String rawUri, String byteRange) {
}