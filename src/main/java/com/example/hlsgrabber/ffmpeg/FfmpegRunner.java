package com.example.hlsgrabber.ffmpeg;

import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.ffmpeg.ffprobe;
import org.bytedeco.javacpp.Loader;

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
     * FFmpeg concat demuxer over a list file, no re-encode (TZ 6.5).
     *
     * @param progress receives lines "out_time=..." for progress reporting
     */
    public void concat(Path listFile, Path out, Consumer<String> progress)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-loglevel"); cmd.add("warning");
        cmd.add("-f"); cmd.add("concat");
        cmd.add("-safe"); cmd.add("0");
        cmd.add("-i"); cmd.add(listFile.toString());
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-bsf:a"); cmd.add("aac_adtstoasc");
        cmd.add("-progress"); cmd.add("pipe:1");
        cmd.add(out.toString());
        run(cmd, progress);
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
     * @param aacBsf apply the ADTS->ASC bitstream filter for TS-sourced audio;
     *               CMAF/fMP4 audio is already in mp4a form and must not be filtered.
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

    /**
     * Concat-list line with ffmpeg escaping rules: wrap in quotes, escape embedded quotes.
     */
    public static String concatLine(String path) {
        String p = path.replace("'", "'\\''");
        return "file '" + p + "'";
    }

    private String run(List<String> cmd, Consumer<String> progress)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (progress != null) {
                    progress.accept(line);
                }
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("ffmpeg exit " + code + ": " + sb);
        }
        return sb.toString();
    }
}