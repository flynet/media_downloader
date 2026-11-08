package com.flynet.hlsgrabber.download;

/** Progress callbacks for downloads; thread-safe, non-EDT safe usage expected. */
public interface ProgressListener {

    /** stage: "segment download", "concatenation", "muxing", "file download". */
    void onProgress(int done, int total, long bytesReceived, String stage);

    /** cooperative cancellation check. */
    boolean isCancelled();

    ProgressListener NOOP = new ProgressListener() {
        @Override public void onProgress(int done, int total, long bytesReceived, String stage) {}
        @Override public boolean isCancelled() { return false; }
    };
}
