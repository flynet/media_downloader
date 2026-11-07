package com.example.hlsgrabber.download;

import com.example.hlsgrabber.util.UrlUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP fetcher on java.net.http.HttpClient (TZ section 2) with the intercepted request
 * headers: Referer, Origin, User-Agent, Cookie (TZ 6.1).
 */
public class HttpFetcher {

    public record Headers(String referer, String origin, String userAgent, String cookie) {
        public static Headers empty() {
            return new Headers(null, null, null, null);
        }
    }

    private final HttpClient client;
    private final Headers headers;

    public HttpFetcher(Headers headers) {
        this.headers = headers == null ? Headers.empty() : headers;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public byte[] get(String url) throws Exception {
        HttpRequest req = build(url);
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int code = resp.statusCode();
        if (code < 200 || code >= 400) {
            throw new java.io.IOException("HTTP " + code + " for " + url);
        }
        return resp.body();
    }

    /**
     * Stream GET to {@code target}: write to {@code target.part} with progress, verify size
     * against {@code minSizeBytes}, then rename. Cancellation raises CancelledException.
     */
    public Path getToFile(String url, Path target, long minSizeBytes, ProgressListener listener)
            throws Exception {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        AtomicLong received = new AtomicLong();
        HttpResponse<Void> resp;
        try (var out = Files.newOutputStream(part)) {
            resp = client.send(build(url), HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> {
                if (chunk.isEmpty()) {
                    return; // end-of-body marker
                }
                byte[] b = chunk.get();
                try {
                    out.write(b);
                    received.addAndGet(b.length);
                    listener.onProgress(0, 0, received.get(), "загрузка файла");
                    if (listener.isCancelled()) {
                        throw new CancellationException("cancelled");
                    }
                } catch (java.io.IOException e) {
                    throw new CompletionException(e);
                }
            }));
        } catch (CompletionException ce) {
            Files.deleteIfExists(part);
            Throwable c = ce.getCause();
            if (c instanceof CancellationException) {
                throw new CancelledException(url);
            }
            if (c instanceof java.io.IOException io) {
                throw io;
            }
            throw new java.io.IOException(c);
        }
        long size = Files.size(part);
        int code = resp.statusCode();
        if (code < 200 || code >= 400) {
            Files.deleteIfExists(part);
            throw new java.io.IOException("HTTP " + code + " for " + url);
        }
        if (size < minSizeBytes) {
            Files.deleteIfExists(part);
            throw new SegmentDownloader.SkipException(
                    "файл меньше минимального размера (" + size + " B)");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        listener.onProgress(0, 0, size, "загрузка завершена");
        return target;
    }

    public String getContentLength(String url) throws Exception {
        HttpRequest req = withHeaders(HttpRequest.newBuilder(URI(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()))
                .build();
        HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
        return resp.headers().firstValue("content-length").orElse(null);
    }

    /** HEAD status probe for constructed-URL viability; never throws on non-2xx. */
    public int headStatus(String url) {
        try {
            HttpRequest req = withHeaders(HttpRequest.newBuilder(URI(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15)))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * GET range probe (Range: bytes=0-0, 1 byte body) for constructed-URL viability:
     * 200/206 means the constructed URL is served fine; 4xx/5xx means the CDN rejects
     * unsigned URLs. Range support is universal; HEAD support is not.
     */
    public int rangeStatus(String url) {
        try {
            HttpRequest req = withHeaders(HttpRequest.newBuilder(URI(url))
                    .GET()
                    .header("Range", "bytes=0-0")
                    .timeout(Duration.ofSeconds(15)))
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return resp.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * GET a byte range ([start, end] inclusive) — the chunked-download primitive for
     * whole-body sources (YouTube googlevideo signed URLs support Range on the full
     * /videoplayback body; the media arrives via POST in the browser but plain GET
     * with the same signed URL + Range works for offline fetching).
     * Exception messages carry {@link UrlUtils#masked} URL only: s/sp/signature params
     * must never reach the log.
     */
    public byte[] rangeGet(String url, long start, long end) throws Exception {
        HttpRequest req = withHeaders(HttpRequest.newBuilder(URI(url))
                .GET()
                .header("Range", "bytes=" + start + "-" + end)
                .timeout(Duration.ofSeconds(120)))
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int code = resp.statusCode();
        if (code == 206) {
            byte[] b = resp.body();
            long expected = end - start + 1;
            if (b.length != expected) {
                throw new java.io.IOException("range size mismatch (" + b.length
                        + " != " + expected + ") for " + UrlUtils.masked(url));
            }
            return b;
        }
        if (code >= 200 && code < 300) {
            // server ignored the Range header: the full body arrived, slice it (start must be 0)
            byte[] b = resp.body();
            if (start == 0 && b.length == end - start + 1) {
                return b;
            }
            throw new java.io.IOException("HTTP " + code + " full-body on range for "
                    + UrlUtils.masked(url));
        }
        throw new java.io.IOException("HTTP " + code + " for " + UrlUtils.masked(url));
    }

    /**
     * Range viability probe on a signed URL: GET bytes=0-0; 206 parses the total size
     * from Content-Range (bytes 0-0/TOTAL); 200 means no Range support (whole body is
     * served) — the caller falls back to a single streaming GET. Any other status /
     * failure returns -1.
     */
    public long probeRangeTotal(String url) {
        try {
            HttpRequest req = withHeaders(HttpRequest.newBuilder(URI(url))
                    .GET()
                    .header("Range", "bytes=0-0")
                    .timeout(Duration.ofSeconds(15)))
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code == 206) {
                String cr = resp.headers().firstValue("content-range").orElse(null);
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        try {
                            return Long.parseLong(cr.substring(slash + 1).trim());
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    }
                }
                return -1;
            }
            if (code >= 200 && code < 300) {
                return 0; // Range unsupported: caller streams the whole body
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }


    private java.net.URI URI(String url) {
        return java.net.URI.create(url);
    }

    private HttpRequest build(String url) {
        return withHeaders(HttpRequest.newBuilder(URI(url))
                .GET()
                .timeout(Duration.ofSeconds(60)))
                .build();
    }

    /**
     * Headers added pair-by-pair: HttpRequest.Builder.headers(String[]) throws
     * IllegalArgumentException on an empty array ("wrong number, 0, of parameters"),
     * which kills HEAD/GET probes when no headers are intercepted.
     */
    private HttpRequest.Builder withHeaders(HttpRequest.Builder b) {
        if (headers.referer() != null) {
            b.header("Referer", headers.referer());
        }
        if (headers.origin() != null) {
            b.header("Origin", headers.origin());
        }
        if (headers.userAgent() != null) {
            b.header("User-Agent", headers.userAgent());
        }
        if (headers.cookie() != null) {
            b.header("Cookie", headers.cookie());
        }
        return b;
    }

    public Map<String, String> baseHeaders() {
        return Map.of();
    }
}