package com.flynet.hlsgrabber.download;

/** Прогресс-колбэки загрузок; thread-safe, ожидается использование вне EDT.
 *  Progress callbacks for downloads; thread-safe, non-EDT safe usage expected. */
public interface ProgressListener {

    /** стадия: «загрузка сегментов», «склейка», «мультиплексирование», «загрузка файла».
     *  stage: "загрузка сегментов", "склейка", "мультиплексирование", "загрузка файла". */
    void onProgress(int done, int total, long bytesReceived, String stage);

    /** кооперативная проверка отмены.
     *  cooperative cancellation check. */
    boolean isCancelled();

    ProgressListener NOOP = new ProgressListener() {
        @Override public void onProgress(int done, int total, long bytesReceived, String stage) {}
        @Override public boolean isCancelled() { return false; }
    };
}
