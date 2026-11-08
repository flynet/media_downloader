package com.flynet.hlsgrabber.browser;

import com.microsoft.playwright.Response;

import java.util.Map;

/**
 * One observed network response, captured cheaply on the Playwright dispatch thread
 * (headers only; no body(), no round-trip calls on dispatch — spike R7 finding).
 * Manifest bodies are fetched lazily in the download worker via record.masterUrl.
 *
 * The Response reference is kept so the pump (sole round-trip caller) can call
 * response.text() once to extract available qualities (heights) from a manifest.
 *
 * SEGMENT: media segment (.m4s/.ts/.mp2t) — feeds the family counter in the pump,
 * never becomes a row of its own (probe3: Pornhub segments carry Content-Type video/mp4,
 * so ct-based classification alone would produce fake MP4 rows).
 *
 * reqHeaders (probe17 finding): the EXACT request headers the browser sent for this URL
 * (lowercase keys: cookie, referer, origin, user-agent). CDN access checks (123av
 * clearpureheart.site HTTP 403) validate the player iframe's Referer and the CDN-domain
 * Cookie, which the top-page snapshot (page.context().cookies(topUrl)) does not carry.
 */
public record SniffEvent(Kind kind, String url, int status, long sizeHint,
                         Map<String, String> reqHeaders, Response response) {

    public enum Kind { MANIFEST, DASH, MP4, SEGMENT }

    public String header(String key) {
        return reqHeaders == null ? null : reqHeaders.get(key);
    }
}
