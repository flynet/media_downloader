package com.example.hlsgrabber.download;

/** Raised when a download is cancelled by the user (cancel button / row action). */
public class CancelledException extends RuntimeException {
    public CancelledException(String url) {
        super("download cancelled: " + url);
    }
}
