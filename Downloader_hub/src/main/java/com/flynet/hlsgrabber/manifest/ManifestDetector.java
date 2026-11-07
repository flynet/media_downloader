package com.flynet.hlsgrabber.manifest;

import com.flynet.hlsgrabber.browser.SniffEvent;
import com.flynet.hlsgrabber.util.UrlUtils;

/**
 * Классифицирует sniffed ответы в виды источников (TZ 5.1). Медиальные сегменты
 * (.m4s/.ts) никогда не становятся отдельными строками (фильтр Stage 2 плана) /
 * Classifies sniffed responses into source kinds (TZ 5.1). Media segments (.m4s/.ts)
 * never become standalone rows (plan Stage 2 filter).
 */
public final class ManifestDetector {

    private ManifestDetector() {
    }

    public static SourceRecord.Kind kindOf(SniffEvent e) {
        switch (e.kind()) {
            case MANIFEST: return SourceRecord.Kind.HLS;
            case DASH: return SourceRecord.Kind.DASH;
            case MP4:
                String path = UrlUtils.pathOf(e.url()).toLowerCase();
                // медиальные сегменты найденных манифестов — не самостоятельные источники
                // media segments of found manifests are not standalone sources
                if (path.endsWith(".m4s") || path.endsWith(".ts") || path.endsWith(".mp2t")) {
                    return null;
                }
                return SourceRecord.Kind.MP4;
            case SEGMENT: return null;
        }
        return null;
    }
}
