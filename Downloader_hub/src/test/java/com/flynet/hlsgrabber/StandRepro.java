package com.flynet.hlsgrabber;

import java.net.HttpURLConnection;
import java.net.URL;

/** Репро StandCheck: поднять E2E-стенд-сервер и fetchнуть /hls-play.html напрямую.
 *  StandCheck repro: start the E2E stand server and fetch /hls-play.html directly. */
public class StandRepro {
    public static void main(String[] args) throws Exception {
        com.sun.net.httpserver.HttpServer s = E2eHarness.startServer();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(E2eHarness.BASE + "/hls-play.html").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            System.out.println("code=" + code + " ct=" + c.getContentType() + " len=" + c.getContentLength());
            byte[] b = c.getInputStream().readAllBytes();
            System.out.println("bytes=" + b.length);
            c.disconnect();
        } catch (Exception e) {
            System.out.println("FETCH FAIL: " + e);
        }
        s.stop(0);
        System.exit(0);
    }
}
