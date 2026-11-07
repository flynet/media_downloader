package com.flynet.hlsgrabber.download;

/** Поднимается, когда загрузку отменил пользователь (cancel-кнопка / действие строки) / Raised when a download is cancelled by the user (cancel button / row action). */
public class CancelledException extends RuntimeException {
    public CancelledException(String url) {
        super("download cancelled: " + url);
    }
}
