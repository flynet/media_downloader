package com.example.hlsgrabber.download;

import com.example.hlsgrabber.ffmpeg.FfmpegRunner;
import com.example.hlsgrabber.hls.HlsParser;
import com.example.hlsgrabber.hls.KeyInfo;
import com.example.hlsgrabber.hls.MasterPlaylist;
import com.example.hlsgrabber.hls.MediaPlaylist;
import com.example.hlsgrabber.hls.Segment;
import com.example.hlsgrabber.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * HLS download pipeline (TZ 6.2): fetch manifest, pick variant, init segment first,
 * segments (parallel, resume, retry, byterange slice), AES-128 decrypt, audio track,
 * FFmpeg concat/mux.
 */
public class SegmentDownloader {

    private static final Logger log = LoggerFactory.getLogger(SegmentDownloader.class);

    private final HttpFetcher fetcher;
    private final FfmpegRunner ffmpeg;
    private final int parallel;
    private final RetryPolicy retry = new RetryPolicy();
    private ProgressListener listener = ProgressListener.NOOP;
    private volatile boolean cancelled;
    private com.example.hlsgrabber.manifest.SourceRecord record;

    public SegmentDownloader(HttpFetcher fetcher, FfmpegRunner ffmpeg, int parallel) {
        this.fetcher = fetcher;
        this.ffmpeg = ffmpeg;
        this.parallel = Math.max(1, parallel);
    }

    public void setListener(ProgressListener l) {
        this.listener = l == null ? ProgressListener.NOOP : l;
    }

    public void setRecord(com.example.hlsgrabber.manifest.SourceRecord rec) {
        this.record = rec;
    }

    /**
     * Resolve a URL for fetching: prefer the browser-requested signed URL recorded by
     * the sniffer (key = filename of the reference); poll briefly for late events
     * (browser requests segments as playback progresses); fall back to construction.
     * phncdn signatures are path-unique: a constructed URL returns HTTP 500.
     */
    private String resolveUrl(String ref, String base, String key) {
        if (record != null) {
            String url = record.recordedUrl(key);
            if (url != null) {
                return url;
            }
        }
        String constructed = UrlUtils.resolve(base, ref);
        // signature-bound CDNs (phncdn) reject constructed URLs (4xx/500): poll the
        // recorded signed URL then. Unsigned sites (123av) serve constructed URLs
        // fine, so probe GET-range first to avoid a 45 s poll per segment.
        int code = fetcher.rangeStatus(constructed);
        if (code >= 200 && code < 400) {
            return constructed;
        }
        if (record != null) {
            log.warn("Запрошенное браузером URL не найдено для {}, конструкция fallback", key);
            String url = waitForRecorded(key);
            if (url != null) {
                return url;
            }
        }
        return constructed;
    }

    private String waitForRecorded(String key) {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled || record.isCancelled()) {
                throw new CancelledException(key);
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException(key);
            }
            String url = record.recordedUrl(key);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /** Recorded map key: filename of a reference (last path segment, no query). */
    private static String recordedKey(String ref) {
        String key = UrlUtils.lastSegment(ref);
        return key.isEmpty() ? ref : key;
    }

    private String fetchText(String url) throws Exception {
        return new String(fetcher.get(url), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Cooperative cancel: stops the segment loop; a running FFmpeg is not killed. */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Download a master playlist: select 1080p variant (TZ 5.2.3), download video,
     * optional audio, mux, write final mp4 into {@code outDir}.
     *
     * @return final file path; temp task dir removed on success, kept on failure.
     */
    public Path runMaster(String masterUrl, Path outDir, String name, int targetHeight)
            throws Exception {
        if (cancelled) {
            throw new CancelledException(masterUrl);
        }
        String text = fetchText(resolveMasterFetch(masterUrl));
        return runMasterText(text, masterUrl, outDir, name, targetHeight);
    }

    /** Master playlist fetch: recorded signed URL by filename key when known. */
    private String resolveMasterFetch(String masterUrl) {
        String key = UrlUtils.lastSegment(UrlUtils.canonicalize(masterUrl));
        if (record != null) {
            String url = record.recordedUrl(key);
            if (url != null) {
                return url;
            }
            url = waitForRecorded(key);
            if (url != null) {
                return url;
            }
        }
        return masterUrl;
    }

    public Path runMasterText(String masterText, String masterUrl, Path outDir, String name,
                              int targetHeight) throws Exception {
        if (!HlsParser.isMaster(masterText)) {
            MediaPlaylist media = HlsParser.parseMedia(masterText);
            Path task = taskDir(outDir);
            Path video = downloadStream(media, masterUrl, task);
            Path finalFile = concatTo(video, task, outDir, name);
            cleanup(task);
            return finalFile;
        }
        MasterPlaylist master = HlsParser.parseMaster(masterText);
        MasterPlaylist.Variant best = pickVariant(master, targetHeight);
        if (best == null) {
            List<String> avail = master.variants().stream()
                    .map(v -> v.height() == null ? "?" : v.height() + "p")
                    .distinct().toList();
            log.info("Нет пригодных вариантов качества (доступно: {}): пропущен",
                    String.join(", ", avail));
            throw new SkipException("no usable variant in master playlist");
        }
        if (targetHeight > 0 && best.height() > targetHeight) {
            log.info("Максимальное доступное качество {}p выше настройки {}p — берём {}p",
                    best.height(), targetHeight, best.height());
        }
        log.info("Выбран {}p-вариант bandwidth={}", best.height(), best.bandwidth());
        Path task = taskDir(outDir);
        String mediaUrl = UrlUtils.resolve(masterUrl, best.rawUri());
        String videoMedia = fetchText(resolveUrl(best.rawUri(), masterUrl,
                recordedKey(best.rawUri())));
        MediaPlaylist media = HlsParser.parseMedia(videoMedia);
        if (media.isLive()) {
            log.info("Live-поток (нет #EXT-X-ENDLIST): пропущен");
            throw new SkipException("live video stream");
        }
        Path video = downloadStream(media, mediaUrl, task);

        MasterPlaylist.MediaEntry audio = master.bestAudio(best.audioGroup());
        Path finalFile;
        if (audio != null && audio.rawUri() != null) {
            String audioUrl = UrlUtils.resolve(masterUrl, audio.rawUri());
            String audioText = fetchText(resolveUrl(audio.rawUri(), masterUrl,
                    recordedKey(audio.rawUri())));
            MediaPlaylist am = HlsParser.parseMedia(audioText);
            if (am.isLive()) {
                log.info("Live-аудио: пропущен");
                throw new SkipException("live audio stream");
            }
            Path audioDir = task.resolve("audio");
            Files.createDirectories(audioDir);
            Path a = downloadStream(am, audioUrl, audioDir);
            if (cancelled) {
                throw new CancelledException(masterUrl);
            }
            Path out = com.example.hlsgrabber.util.FileNames.build(outDir.toFile(), name, ".mp4").toPath();
            listener.onProgress(0, 0, 0, "мультиплексирование");
            // both inputs are already mp4 with ASC audio: concatHls applied
            // aac_adtstoasc to TS-sourced audio, CMAF audio is mp4a from the start
            ffmpeg.mux(video, a, out, line -> log.debug("ffmpeg mux: {}", line), false);
            checkAssembled(out, Files.size(video) + Files.size(a));
            finalFile = out;
        } else {
            finalFile = concatTo(video, task, outDir, name);
        }
        cleanup(task);
        return finalFile;
    }

    /** Download one media playlist (segments + init + key + concat) into taskDir. */
    private Path downloadStream(MediaPlaylist media, String mediaUrl, Path taskDir)
            throws Exception {
        KeyInfo key = media.playlistKey();
        byte[] keyBytes = null;
        Decryptor decryptor = null;
        byte[] fixedIv = null;
        if (key != null) {
            if (key.isDrm()) {
                log.info("Контент защищён DRM ({}), скачивание невозможно", key.method());
                throw new SkipException("DRM content");
            }
            if (key.isAes128()) {
                String keyUrl = resolveUrl(key.uri(), mediaUrl, recordedKey(key.uri()));
                keyBytes = fetcher.get(keyUrl);
                decryptor = new Decryptor(keyBytes);
                if (key.ivHex() != null) {
                    fixedIv = Decryptor.parseIv(key.ivHex());
                }
            }
        }

        final Decryptor dec = decryptor;
        final byte[] iv = fixedIv;

        List<Path> files = new ArrayList<>();
        long bytes = 0;
        int total = media.segments().size();
        // CMAF/fMP4 media playlist (phncdn style): #EXT-X-MAP init + moof/mdat fragments.
        // The concat demuxer cannot read moof/mdat .m4s: its result is a header-only file.
        boolean cmaf = media.mapUri() != null;
        String segExt = cmaf && total > 0 ? segmentExt(media.segmentAt(0).rawUri(), ".mp4") : ".ts";
        listener.onProgress(0, total, 0, "загрузка сегментов");
        if (media.mapUri() != null) {
            String initUrl = resolveUrl(media.mapUri(), mediaUrl, recordedKey(media.mapUri()));
            byte[] init = fetcher.get(initUrl);
            Path initFile = taskDir.resolve("init" + segmentExt(media.mapUri(), ".mp4"));
            Files.write(initFile, init);
            files.add(initFile);
            bytes += init.length;
            log.info("Init-сегмент {} байт", init.length);
        }

        log.info("Сегментов: {}", total);
        List<Future<Path>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        // per-remote-file cache for byterange slicing
        Map<String, byte[]> fileCache = new HashMap<>();
        int done = 0;
        try {
            for (int i = 0; i < total; i++) {
                Segment seg = media.segmentAt(i);
                futures.add(pool.submit(() -> {
                    String url = resolveUrl(seg.rawUri(), mediaUrl, recordedKey(seg.rawUri()));
                    Path local = taskDir.resolve(String.format("seg%06d%s", seg.sequence(), segExt));
                    if (Files.exists(local) && Files.size(local) > 0) {
                        return local; // resume: already downloaded
                    }
                    byte[] body = fetchSegment(url, seg, fileCache);
                    byte[] plain = dec != null
                            ? dec.decrypt(body, iv != null
                                    ? iv : Decryptor.ivFromSequence(seg.sequence()))
                            : body;
                    Files.write(local, plain);
                    return local;
                }));
            }
            for (Future<Path> f : futures) {
                Path segFile = f.get();
                files.add(segFile);
                done++;
                bytes += Files.size(segFile);
                listener.onProgress(done, total, bytes, "загрузка сегментов");
                if (cancelled) {
                    pool.shutdownNow();
                    throw new CancelledException(mediaUrl);
                }
            }
        } finally {
            pool.shutdown();
        }
        if (cancelled) {
            throw new CancelledException(mediaUrl);
        }

        Path stream = taskDir.resolve("stream.mp4");
        if (cmaf) {
            // byte-concat init + segments in playlist order, then remux the fragmented
            // stream into a standard playable mp4. concat demuxer drops moof/mdat fragments.
            Path raw = taskDir.resolve("raw.mp4");
            listener.onProgress(total, total, bytes, "сборка потока");
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(raw))) {
                for (Path p : files) {
                    Files.copy(p, os);
                }
            }
            listener.onProgress(total, total, bytes, "remux");
            ffmpeg.remux(raw, stream, line -> {
                if (line.startsWith("out_time=")) {
                    log.debug("remux: {}", line);
                }
            });
            checkAssembled(stream, bytes);
            return stream;
        }
        // MPEG-TS: assemble through the HLS demuxer over a local playlist. The HLS
        // demuxer lays the segments onto the playlist timeline (exactly what an HLS
        // player does), while the concat demuxer re-bases every file by its own
        // duration estimate and leaves a hole of ~0.1-0.2 s at each segment boundary —
        // the file then plays stuttery and is ~2.5 % longer than the content.
        Path playlist = taskDir.resolve("local.m3u8");
        Files.write(playlist,
                localPlaylistText(media, files).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        listener.onProgress(total, total, bytes, "склейка");
        ffmpeg.concatHls(playlist, stream, line -> {
            if (line.startsWith("out_time=")) {
                log.debug("склейка: {}", line);
            }
        });
        // Safety net: the HLS demuxer opens every segment as a separate input, so a site
        // whose segments carry no PAT/PMT of their own would lose them. If the result is
        // shorter than the playlist declares, fall back to the byte-join assembly, which
        // works for any MPEG-TS and keeps the source timestamps.
        double expected = expectedDuration(media);
        double actual = ffmpeg.duration(stream);
        if (!looksComplete(expected, actual)) {
            log.warn("Сборка через HLS-демуксер короче ожидаемого ({} с вместо {} с) — "
                    + "сборка байтовой склейкой", actual, expected);
            Path joined = taskDir.resolve("joined.ts");
            joinBytes(files, joined);
            ffmpeg.remuxTs(joined, stream, line -> {
                if (line.startsWith("out_time=")) {
                    log.debug("склейка (байтовая): {}", line);
                }
            });
            double joinedActual = ffmpeg.duration(stream);
            if (!looksComplete(expected, joinedActual)) {
                // The playlist's EXTINF values may simply be inaccurate — that is not a
                // reason to fail a download that used to succeed; report and continue.
                log.warn("Собранный поток ({} с) короче суммы EXTINF ({} с) — "
                        + "возможно, неточные длительности в плейлисте", joinedActual, expected);
            }
        }
        checkAssembled(stream, bytes);
        return stream;
    }

    /** Sum of the #EXTINF durations of the playlist — the expected length of the stream. */
    static double expectedDuration(MediaPlaylist media) {
        double sum = 0;
        for (Segment s : media.segments()) {
            sum += s.duration();
        }
        return sum;
    }

    /**
     * Is the assembled stream as long as the playlist declares? A shorter result means
     * segments were dropped (unreadable standalone segment); a longer one is allowed —
     * the source itself may carry holes, and hiding them is not our job.
     */
    static boolean looksComplete(double expectedSec, double actualSec) {
        if (expectedSec <= 0 || actualSec < 0) {
            return true; // нет данных — не считаем потерю сегментов
        }
        return actualSec >= expectedSec * 0.98;
    }

    /** Byte-joins the downloaded segments into one MPEG-TS stream (fallback assembly). */
    private static void joinBytes(List<Path> files, Path out) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            for (Path p : files) {
                Files.copy(p, os);
            }
        }
    }

    /**
     * Local HLS media playlist over the already downloaded segments (relative names:
     * the playlist lives in the same directory). Keeps the original #EXTINF durations
     * so the HLS demuxer reproduces the source timeline without holes.
     * package-private for unit testing.
     */
    static String localPlaylistText(MediaPlaylist media, List<Path> segmentFiles) {
        List<Segment> segs = media.segments();
        int n = Math.min(segs.size(), segmentFiles.size());
        double max = 0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, segs.get(i).duration());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(max)).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(media.mediaSequence()).append('\n');
        for (int i = 0; i < segmentFiles.size(); i++) {
            double dur = i < segs.size() ? segs.get(i).duration() : max;
            sb.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", dur)).append(",\n");
            sb.append(segmentFiles.get(i).getFileName().toString()).append('\n');
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();    }

    private byte[] fetchSegment(String url, Segment seg, Map<String, byte[]> cache) throws Exception {
        if (seg.byteRange() != null) {
            byte[] full = cache.get(url);
            if (full == null) {
                full = fetchWithRetry(url);
                cache.put(url, full);
            }
            return slice(full, seg.byteRange());
        }
        return fetchWithRetry(url);
    }

    private byte[] fetchWithRetry(String url) throws Exception {
        final byte[][] holder = new byte[1][];
        retry.run(url, () -> {
            try {
                holder[0] = fetcher.get(url);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return holder[0];
    }

    /**
     * Extension of a segment URI path (query/fragment stripped); def when the path
     * carries no usable extension. phncdn serves ".m4s" fragments, 123av serves ".ts".
     * package-private for unit testing.
     */
    static String segmentExt(String uri, String def) {
        String p = uri;
        int q = p.indexOf('?');
        if (q < 0) {
            q = p.indexOf('#');
        }
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int dot = p.lastIndexOf('.');
        if (dot < 0 || dot <= p.lastIndexOf('/')) {
            return def;
        }
        String ext = p.substring(dot + 1);
        if (ext.length() > 4) {
            return def;
        }
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9')) {
                return def;
            }
        }
        return "." + ext;
    }

    /**
     * Honest failure for header-only assembly: when the produced stream is a tiny
     * fraction of the downloaded segment bytes, the concat/remux path silently dropped
     * the payload (concat demuxer over moof/mdat fragments), so the task must not
     * report success with a header-only unplayable file.
     */
    private static void checkAssembled(Path stream, long sourceBytes) throws IOException {
        long size = Files.exists(stream) ? Files.size(stream) : 0;
        if (sourceBytes > 100_000 && size * 10 < sourceBytes) {
            throw new IOException("Собранный поток (" + size + " B) несоизмерим со скачанными "
                    + "сегментами (" + sourceBytes + " B): сегменты не попали в финальный файл");
        }
    }

    /** #EXT-X-BYTERANGE: "len" or "len@offset" (TZ 6.2). */
    static byte[] slice(byte[] full, String byteRange) {
        String[] parts = byteRange.split("@");
        int len = Integer.parseInt(parts[0].trim());
        int offset = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
        return java.util.Arrays.copyOfRange(full, offset, offset + len);
    }

    /**
     * Max-quality selection (user requirement): highest variant not exceeding the cap
     * (targetHeight, 0 = no cap); when every variant is above the cap, fall back to the
     * overall highest. null only when the master carries no usable (heighted) variant.
     * package-private for unit testing.
     */
    MasterPlaylist.Variant pickVariant(MasterPlaylist master, int cap) {
        MasterPlaylist.Variant best = pickCap(master, cap);
        if (best != null) {
            return best;
        }
        // cap below every variant: take the best available quality
        return pickCap(master, 0);
    }

    /**
     * Highest variant with height <= cap (cap 0 = no cap), ties broken on bandwidth.
     * Heights compared as int: Integer == Integer compares references for values
     * outside the -128..127 cache, which silently kills the bandwidth tie-break.
     */
    private static MasterPlaylist.Variant pickCap(MasterPlaylist master, int cap) {
        MasterPlaylist.Variant best = null;
        for (MasterPlaylist.Variant v : master.variants()) {
            Integer h = v.height();
            if (h == null || h <= 0) {
                continue;
            }
            if (cap > 0 && h > cap) {
                continue;
            }
            if (best == null) {
                best = v;
                continue;
            }
            int bh = best.height();
            if (h > bh || (h == bh && v.bandwidth() > best.bandwidth())) {
                best = v;
            }
        }
        return best;
    }

    private Path concatTo(Path stream, Path taskDir, Path outDir, String name) throws Exception {
        Path out = com.example.hlsgrabber.util.FileNames.build(outDir.toFile(), name, ".mp4").toPath();
        Files.copy(stream, out);
        return out;
    }

    private Path taskDir(Path outDir) throws IOException {
        String id = Long.toString(System.nanoTime(), 36);
        Path task = outDir.resolve(".tmp").resolve(id);
        Files.createDirectories(task);
        return task;
    }

    private void cleanup(Path taskDir) throws IOException {
        try (var walk = Files.walk(taskDir)) {
            List<Path> all = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
        // the .tmp parent must not litter the downloads folder after a successful run
        Path base = taskDir.getParent();
        if (base != null && ".tmp".equals(String.valueOf(base.getFileName()))) {
            try (var walk = Files.walk(base, 1)) {
                if (walk.count() == 1) {
                    Files.deleteIfExists(base);
                    log.info("Папка .tmp удалена (задача завершена)");
                }
            } catch (IOException e) {
                log.debug("cleanup .tmp parent: {}", e.getMessage());
            }
        }
    }

    /** Sentinel for filters that skip the source (live/DRM/no-1080p). */
    public static class SkipException extends Exception {
        public SkipException(String msg) {
            super(msg);
        }
    }
}