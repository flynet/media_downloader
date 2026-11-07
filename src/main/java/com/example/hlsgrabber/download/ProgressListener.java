package com.example.hlsgrabber.download;

/** Progress callbacks for downloads; thread-safe, non-EDT safe usage expected. */
public interface ProgressListener {

    /** stage: "загрузка сегментов", "склейка", "мультиплексирование", "загрузка файла". */
    void onProgress(int done, int total, long bytesReceived, String stage);

    /** cooperative cancellation check. */
    boolean isCancelled();

    ProgressListener NOOP = new ProgressListener() {
        @Override public void onProgress(int done, int total, long bytesReceived, String stage) {}
        @Override public boolean isCancelled() { return false; }
    };
}
