package com.flynet.hlsgrabber.ffmpeg;

import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.ffmpeg.ffprobe;
import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * FFmpeg via bytedeco (TZ section 2): Loader.load + ProcessBuilder, no system FFmpeg.
 * Concat without re-encode, -c copy, -bsf:a aac_adtstoasc (TZ 6.5).
 */
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);
    /** How many ffmpeg warning lines to keep in the log (the rest are only counted). */
    private static final int MAX_WARN_LINES = 20;

    private final String ffmpegPath;
    private final String ffprobePath;

    /**
     * Canonical bytedeco way: the properties of ffmpeg.class and ffprobe.class
     * register the executables, so Loader.load() extracts and returns the paths
     * (works from plain classpath AND from the shaded fat jar).
     * Loader.cacheResource("ffmpeg.exe") fails inside a fat jar: resource lookup
     * is relative to the caller class package (R4 spike used Loader.load).
     */
    public FfmpegRunner() throws IOException {
        this.ffmpegPath = Loader.load(ffmpeg.class);
        this.ffprobePath = Loader.load(ffprobe.class);
    }

    /** For spike/selftest: FFmpeg version line. */
    public String version() throws IOException, InterruptedException {
        String out = run(List.of(ffmpegPath, "-version"), null);
        String first = out.split("\n")[0];
        return first;
    }

    /**
     * Concatenates MPEG-TS segments through the HLS demuxer over a local media playlist.
     *
     * <p>Why not the concat demuxer: it re-bases every input file by its own duration
     * estimate, which for MPEG-TS is slightly longer than the real payload (the last
     * frame duration is added on top). The result is a hole of ~0.1-0.2 s at every
     * segment boundary — the file plays "stuttery" and its timeline is ~2.5 % longer
     * than the content. The HLS demuxer instead lays the segments onto the timeline
     * declared by the playlist, which is exactly what an HLS player does, so the
     * assembled file has no gaps (verified: 0 holes on a 4-segment fixture, and on a
     * 1009-segment movie stream).
     *
     * @param progress receives lines "out_time=..." for progress reporting
     */
    public void concatHls(Path playlist, Path out, Consumer<String> progress)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-loglevel"); cmd.add("warning");
        cmd.add("-f"); cmd.add("hls");
        cmd.add("-i"); cmd.add(playlist.toString());
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-bsf:a"); cmd.add("aac_adtstoasc");
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());
        run(cmd, progress);
    }

    /**
     * Fallback assembly: remuxes a byte-joined MPEG-TS stream (segments concatenated
     * as raw bytes) into MP4. MPEG-TS is designed to be byte-joinable, so this works
     * even for sites whose segments carry no PAT/PMT of their own — the one case the
     * HLS demuxer cannot read (it opens every segment as a separate input).
     * Like the HLS path it preserves the source timestamps, so there are no holes.
     */
    public void remuxTs(Path joinedTs, Path out, Consumer<String> progress)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-loglevel"); cmd.add("warning");
        cmd.add("-i"); cmd.add(joinedTs.toString());
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-bsf:a"); cmd.add("aac_adtstoasc");
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());
        run(cmd, progress);
    }

    /**
     * Duration of a media file in seconds (ffprobe, metadata only).
     * Returns -1 when it cannot be determined.
     */
    public double duration(Path file) throws IOException, InterruptedException {
        String out = run(List.of(ffprobePath, "-v", "error", "-show_entries", "format=duration",
                "-of", "csv=p=0", file.toString()), null);
        try {
            return Double.parseDouble(out.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Remuxes an assembled MP4 (byte-concatenated fragmented-MP4/CMAF stream)
     * into a standard playable MP4. Streams are copied without re-encoding.
     */
    public void remux(Path stream, Path out, Consumer<String> progress)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-loglevel"); cmd.add("warning");
        cmd.add("-i"); cmd.add(stream.toString());
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());
        run(cmd, progress);
    }

    /**
     * Mux video + audio (separate tracks, TZ 6.2/6.3) without re-encode.
     *
     * @param aacBsf apply the ADTS->ASC
     *               bitstream filter for TS-sourced audio; CMAF/fMP4 audio is already in
     *               mp4a form and must not be filtered.
     */
    public void mux(Path video, Path audio, Path out, Consumer<String> progress, boolean aacBsf)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-loglevel"); cmd.add("warning");
        cmd.add("-i"); cmd.add(video.toString());
        cmd.add("-i"); cmd.add(audio.toString());
        cmd.add("-map"); cmd.add("0:v");
        cmd.add("-map"); cmd.add("1:a");
        cmd.add("-c"); cmd.add("copy");
        if (aacBsf) {
            cmd.add("-bsf:a"); cmd.add("aac_adtstoasc");
        }
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());
        run(cmd, progress);
    }

    /** ffprobe -show_format -show_streams, text output for the log and CLI checks. */
    public String probe(Path file) throws IOException, InterruptedException {
        return run(List.of(ffprobePath, "-v", "error", "-show_format", "-show_streams",
                "-of", "default", file.toString()), null);
    }

    private String run(List<String> cmd, Consumer<String> progress)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        int warnLines = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (progress != null) {
                    progress.accept(line);
                }
                // ffmpeg warnings used to be swallowed by the progress consumer - keep
                // them in the log (capped), they are the only trace of stream trouble.
                if (!line.startsWith("out_time=") && !line.isBlank() && !line.startsWith("frame=")
                        && !line.startsWith("bitrate=") && !line.startsWith("total_size=")
                        && !line.startsWith("out_time_ms=") && !line.startsWith("dup_frames=")
                        && !line.startsWith("drop_frames=") && !line.startsWith("speed=")
                        && !line.startsWith("progress=")) {
                    warnLines++;
                    if (warnLines <= MAX_WARN_LINES) {
                        log.warn("ffmpeg: {}", line);
                    }
                }
            }
        }
        int code = p.waitFor();
        if (warnLines > MAX_WARN_LINES) {
            log.warn("ffmpeg: {} more warning lines (first {} shown)",
                    warnLines - MAX_WARN_LINES, MAX_WARN_LINES);
        }
        if (code != 0) {
            throw new IOException("ffmpeg exit " + code + ": " + sb);
        }
        return sb.toString();
    }
}