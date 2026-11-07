package com.flynet.hlsgrabber.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * URL-хелперы: разрешение относительных ссылок плейлистов, канонизация URL для dedup /
 * URL helpers: resolving relative playlist references, canonicalizing URLs for dedup.
 */
public final class UrlUtils {

    /** Query-параметры, обычно несущие volatile токены (подписи, сессии, TTL) / Query parameters that commonly carry volatile tokens (signatures, sessions, TTL). */
    private static final Set<String> VOLATILE_PARAMS = Set.of(
            "token", "sig", "signature", "auth", "session", "key", "exp", "expires",
            "secure", "ttl", "ts", "timestamp", "time", "hash", "h", "s", "v",
            // phncdn CDN: h= подпись, e= expiry, f= флаг — всё volatile per-file
            // phncdn CDN: h= signature, e= expiry, f= flag — all per-file volatile
            "e", "f");

    private UrlUtils() {
    }

    /**
     * Разрешает ссылку от базового URL манифеста, сохраняя base query, если ссылка
     * своего не несёт (наследование token/signature), по TZ 6.1 /
     * Resolve a reference from a base manifest URL, preserving the base query if the
     * reference carries none (token/signature inheritance), per TZ 6.1.
     */
    public static String resolve(String base, String ref) {
        if (ref == null || ref.isEmpty()) {
            return base;
        }
        URI baseUri;
        try {
            baseUri = new URI(base);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad base url: " + base, e);
        }
        String target;
        try {
            URI refUri = new URI(ref);
            if (refUri.isAbsolute()) {
                target = ref;
            } else {
                URI resolved = baseUri.resolve(refUri);
                String mergedQuery = mergeQuery(baseUri.getRawQuery(), resolved.getRawQuery());
                // композиция из RAW-компонентов: многопараметровый URI ctor повторно
                // кодирует уже закодированный query (%2B -> %252B), что ломает CDN-подписи
                // (phncdn h= параметр -> HTTP 500 на каждом сегменте)
                // compose from RAW components: the multi-arg URI ctor re-encodes an
                // already-encoded query (%2B -> %252B), which breaks CDN signatures
                // (phncdn h= param -> HTTP 500 on every segment)
                target = composeRaw(resolved, mergedQuery);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad ref url: " + ref, e);
        }
        return target;
    }

    /**
     * Композиция URL-строки из raw (уже percent-encoded) компонентов БЕЗ повторного
     * кодирования: scheme://userInfo@host:port/rawPath?rawQuery /
     * Compose a URL string from raw (already percent-encoded) components WITHOUT
     * re-encoding them: scheme://userInfo@host:port/rawPath?rawQuery.
     */
    private static String composeRaw(URI uri, String rawQuery) {
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append(':');
        }
        String host = uri.getHost();
        if (host != null) {
            sb.append("//");
            String rawUserInfo = uri.getRawUserInfo();
            if (rawUserInfo != null) {
                sb.append(rawUserInfo).append('@');
            }
            sb.append(host);
            int port = uri.getPort();
            if (port > 0 && port != 80 && port != 443) {
                sb.append(':').append(port);
            }
        } else if (uri.getRawAuthority() != null) {
            sb.append("//").append(uri.getRawAuthority());
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            sb.append(rawPath);
        }
        if (rawQuery != null) {
            sb.append('?').append(rawQuery);
        }
        return sb.toString();
    }

    private static String mergeQuery(String baseQuery, String refQuery) {
        if (refQuery != null && !refQuery.isEmpty()) {
            return refQuery;
        }
        return baseQuery;
    }

    /**
     * Канон-форма для dedup (TZ 5.2.4): убрать fragment, убрать volatile query-параметры /
     * Canonical form for dedup (TZ 5.2.4): drop fragment, drop volatile query params.
     */
    public static String canonicalize(String url) {
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();
            String kept = null;
            if (query != null && !query.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    String name = eq >= 0 ? part.substring(0, eq) : part;
                    if (isVolatile(name)) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append('&');
                    }
                    sb.append(part);
                }
                kept = sb.length() == 0 ? null : sb.toString();
            }
            // raw композиция (см. composeRaw): повторное кодирование raw query здесь
            // портит signature-параметры так же, как в resolve()
            // raw composition (see composeRaw): re-encoding a raw query here corrupts
            // signature-carrying params the same way as in resolve()
            return composeRaw(uri, kept);
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private static boolean isVolatile(String name) {
        String n = name.toLowerCase();
        return VOLATILE_PARAMS.contains(n)
                || n.endsWith("token") || n.endsWith("sig") || n.endsWith("sig2")
                || n.equals("st") || n.equals("et") || n.equals("ke");
    }

    /**
     * true когда Content-Type помечает HLS/DASH манифест, или URL кончается .m3u8/.mpd (TZ 5.1) /
     * true when Content-Type marks an HLS/DASH manifest, or URL ends with .m3u8/.mpd (TZ 5.1).
     */
    public static boolean isManifestCandidate(String url, String contentType) {
        String path = pathOf(url).toLowerCase();
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            return true;
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            return ct.startsWith("application/vnd.apple.mpegurl")
                    || ct.startsWith("application/x-mpegurl")
                    || ct.startsWith("audio/mpegurl")
                    || ct.startsWith("application/dash+xml");
        }
        return false;
    }

    /**
     * true когда Content-Type помечает direct MP4, или URL кончается .mp4 (TZ 5.1, query игнорируется) /
     * true when Content-Type marks direct MP4, or URL ends with .mp4 (TZ 5.1, query ignored).
     */
    public static boolean isDirectMp4Candidate(String url, String contentType) {
        String path = pathOf(url).toLowerCase();
        if (path.endsWith(".mp4")) {
            return true;
        }
        return contentType != null && contentType.toLowerCase().startsWith("video/mp4");
    }

    /**
     * true когда path URL — медиальный сегмент (.m4s/.ts/.mp2t): сегменты принадлежат
     * плейлисту/манифесту и никогда не должны становиться самостоятельными источниками
     * (TZ 5.1, находка реальных сайтов: сегменты Pornhub несут Content-Type video/mp4) /
     * true when the URL path is a media segment (.m4s/.ts/.mp2t) — segments belong to a
     * playlist/manifest and must never become standalone sources (TZ 5.1, real-site finding:
     * Pornhub segments carry Content-Type video/mp4).
     */
    public static boolean isSegmentPath(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".m4s") || p.endsWith(".ts") || p.endsWith(".mp2t")
                // phncdn: AES-128 key-файл <dir>/index-v1-a1.key?h=.., подписан per path
                // phncdn: AES-128 key file <dir>/index-v1-a1.key?h=.., signed per path
                || p.endsWith(".key");
    }

    /**
     * Каноническая директория URL: канонизированная форма без последнего path-сегмента,
     * trailing slash сохранён (напр. scheme://host/dir/). Используется для family/record
     * matching /
     * Canonical directory of a URL: canonicalized form without the last path segment,
     * trailing slash kept (e.g. scheme://host/dir/). Used for family/record matching.
     */
    public static String canonicalDir(String url) {
        String c = canonicalize(url);
        int idx = c.lastIndexOf('/');
        return idx > 0 ? c.substring(0, idx + 1) : "";
    }

    /** Последний path-сегмент канонизированного URL (query/fragment сняты) / Last path segment of the canonicalized URL (query/fragment stripped). */
    public static String lastSegment(String url) {
        String c = canonicalize(url);
        int idx = c.lastIndexOf('/');
        return idx >= 0 ? c.substring(idx + 1) : c;
    }

    /** path-часть URL, query/fragment сняты (TZ: "do query") / path part of URL, query/fragment stripped (TZ: "do query"). */
    public static String pathOf(String url) {
        try {
            URI uri = new URI(url);
            return uri.getPath() == null ? "" : uri.getPath();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    /** scheme://host[:port] origin из URL страницы (для Origin request-заголовка) / scheme://host[:port] origin from a page URL (for the Origin request header). */
    public static String originOf(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                sb.append(':').append(uri.getPort());
            }
            return sb.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * scheme://host/path с снятым query+fragment — безопасная форма для log/exception
     * сообщений. YouTube googlevideo stream URL несут s/sp/signature-параметры, которые
     * никогда не должны попадать в консоль или log-файл; phncdn h=/e=/f= similarly /
     * scheme://host/path with query+fragment STRIPPED — the safe form for log/exception
     * messages. YouTube googlevideo stream URLs carry s/sp/signature parameters that
     * must never reach the console or the log file; phncdn h=/e=/f= params likewise.
     */
    public static String masked(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return pathOf(url);
            }
            StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            int port = uri.getPort();
            if (port > 0 && port != 80 && port != 443) {
                sb.append(':').append(port);
            }
            String rawPath = uri.getRawPath();
            if (rawPath != null) {
                sb.append(rawPath);
            }
            return sb.toString();
        } catch (URISyntaxException e) {
            int q = url.indexOf('?');
            int h = url.indexOf('#');
            String cut = q >= 0 ? url.substring(0, q) : url;
            if (h >= 0 && h < cut.length()) {
                cut = cut.substring(0, h);
            }
            return cut;
        }
    }

    /**
     * YouTube video id из watch/shorts/youtu.be URL, null иначе. Канонический ключ
     * YouTube-строк — {@code "yt:" + id}: канонизация watch URL сбрасывает {@code v}
     * параметр (VOLATILE_PARAMS), что при SPA-навигации схлопнуло бы все видео
     * youtube.com в один ключ строки /
     * YouTube video id from a watch/shorts/youtu.be URL, null otherwise.
     * Canonical key for YouTube rows is {@code "yt:" + id}: watch URL canonicalization
     * drops the {@code v} param (VOLATILE_PARAMS), which would collapse every video of
     * youtube.com into a single row key under SPA navigation.
     */
    public static String youtubeVideoId(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String h = host.toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String id = null;
            if (h.equals("www.youtube.com") || h.equals("youtube.com")
                    || h.equals("m.youtube.com") || h.equals("www.youtube-nocookie.com")) {
                if (path.startsWith("/watch")) {
                    String q = uri.getRawQuery();
                    if (q != null) {
                        for (String part : q.split("&")) {
                            if (part.startsWith("v=")) {
                                id = part.substring(2);
                                break;
                            }
                        }
                    }
                } else if (path.startsWith("/shorts/")) {
                    String rest = path.substring(8);
                    int slash = rest.indexOf('/');
                    id = slash >= 0 ? rest.substring(0, slash) : rest;
                }
            } else if (h.equals("youtu.be")) {
                String rest = path.startsWith("/") ? path.substring(1) : path;
                int slash = rest.indexOf('/');
                id = slash >= 0 ? rest.substring(0, slash) : rest;
            }
            if (id == null || id.length() != 11) {
                return null;
            }
            return id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}