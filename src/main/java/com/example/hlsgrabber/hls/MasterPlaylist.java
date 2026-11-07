package com.example.hlsgrabber.hls;

import java.util.List;

/**
 * HLS master playlist (TZ 6.2, 5.2.3): variants with #EXT-X-STREAM-INF and
 * #EXT-X-MEDIA entries.
 *
 * @param variants variant streams
 * @param media    #EXT-X-MEDIA entries (audio tracks etc.)
 */
public record MasterPlaylist(List<Variant> variants, List<MediaEntry> media) {

    public record Variant(String rawUri, long bandwidth, Integer height, Integer width,
                          String audioGroup, String codecs, String name) {
    }

    public record MediaEntry(String type, String groupId, String name, String rawUri,
                             boolean isDefault, String language) {
    }

    /** Variants with the exact target height (TZ 5.2.3: height == 1080). */
    public List<Variant> variantsWithHeight(int targetHeight) {
        return variants.stream()
                .filter(v -> v.height() != null && v.height() == targetHeight)
                .sorted((a, b) -> Long.compare(b.bandwidth(), a.bandwidth()))
                .toList();
    }

    /** Audio track for a variant: DEFAULT=YES in its AUDIO group, else the first audio entry. */
    public MediaEntry bestAudio(String audioGroup) {
        if (audioGroup == null) {
            return null;
        }
        List<MediaEntry> group = media.stream()
                .filter(m -> "AUDIO".equals(m.type()) && audioGroup.equals(m.groupId()))
                .toList();
        if (group.isEmpty()) {
            return null;
        }
        return group.stream()
                .filter(MediaEntry::isDefault)
                .findFirst()
                .orElse(group.get(0));
    }
}