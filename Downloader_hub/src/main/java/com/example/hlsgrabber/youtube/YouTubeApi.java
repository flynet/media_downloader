package com.example.hlsgrabber.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент inner-tube player API (YouTube).
 *
 * Inner-tube player API client (YouTube).
 *
 * <p>Запрос выполняется из контекста браузера (cookies + visitorData страницы просмотра),
 * потому что обычный HTTP-вызов из приложения отвергается бот-проверкой.
 * The request is issued from the browser context (cookies + visitorData of the
 * watch page), because a plain HTTP call from the app is rejected with a bot-check.
 * Клиент VISIONOS используется намеренно: он отвечает CLASSIC подписанными URL потоков
 * (обычным {@code url}) для каждого адаптивного формата, тогда как клиент WEB отдаёт
 * только серверный ABR-эндпоинт (см. YouTubePlayer).
 * The VISIONOS client is used deliberately: it still answers with CLASSIC signed
 * stream URLs (plain {@code url}) for every adaptive format, while the WEB client
 * only exposes the server-side ABR endpoint (see YouTubePlayer for details).
 *
 * <p>Никто здесь не обращается к YouTube вне браузерной сессии, и подписанные значения
 * никогда не логируются.
 * Nothing here talks to YouTube outside the browser session, and no signed value
 * is ever logged.
 */
public final class YouTubeApi {

    private static final Logger log = LoggerFactory.getLogger(YouTubeApi.class);

    /** Inner-tube id клиента VISIONOS.
     *  Inner-tube client id of the VISIONOS client. */
    public static final int CLIENT_ID = 101;
    public static final String CLIENT_NAME = "VISIONOS";
    public static final String CLIENT_VERSION = "1.02";

    /** Safari UA клиента VISIONOS (отправляется внутри inner-tube контекста).
     *  Safari UA of the VISIONOS client (sent inside the inner-tube context). */
    public static final String CLIENT_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    /** Публичный web inner-tube ключ (тот же, что использует страница просмотра).
     *  Public web inner-tube key (the same one the watch page uses). */
    public static final String API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

    private YouTubeApi() {
    }

    /**
     * Собирает inner-tube player payload для id видео.
     * Builds the inner-tube player payload for a video id.
     *
     * @param videoId     11-символьный id видео / 11-char video id
     * @param visitorData visitorData страницы просмотра (обязателен: без него
     *                     ответ — бот-проверка) / visitorData of the watch page
     *                     (required: without it the answer is a bot-check)
     */
    public static String playerPayload(String videoId, String visitorData) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"videoId\":\"").append(videoId).append("\",\"context\":{\"client\":{");
        sb.append("\"clientName\":\"").append(CLIENT_NAME).append("\",");
        sb.append("\"clientVersion\":\"").append(CLIENT_VERSION).append("\",");
        sb.append("\"hl\":\"en\",\"gl\":\"US\",");
        sb.append("\"userAgent\":\"").append(CLIENT_UA).append("\",");
        sb.append("\"deviceMake\":\"Apple\",\"deviceModel\":\"RealityDevice17,1\",");
        sb.append("\"osName\":\"visionOS\",\"osVersion\":\"26.5.23O471\"");
        if (visitorData != null && !visitorData.isBlank()) {
            sb.append(",\"visitorData\":\"").append(visitorData).append("\"");
        }
        sb.append("}},\"contentCheckOk\":true,\"racyCheckOk\":true}");
        return sb.toString();
    }

    /**
     * JS-фрагмент, выполняемый внутри страницы: читает visitorData страницы и вызывает
     * inner-tube player API от origins страницы (cookies включены).
     * JS snippet executed inside the page: reads the page's visitorData and calls the
     * inner-tube player API from the page origin (cookies included).
     *
     * <p>Хранится как константа, чтобы страничный код был виден в одном месте.
     * Kept as a constant so the page-side code is visible in one place.
     */
    public static final String PAGE_FETCH_SCRIPT = """
            async (args) => {
              try {
                let visitor = args.visitor;
                if (!visitor) {
                  try {
                    visitor = (window.ytcfg.get('INNERTUBE_CONTEXT').client || {}).visitorData || '';
                  } catch (e) { visitor = ''; }
                }
                const payload = JSON.parse(args.payloadTemplate.replace('__VISITOR__', visitor));
                const r = await fetch('/youtubei/v1/player?key=' + args.key, {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/json',
                    'X-YouTube-Client-Name': args.clientName,
                    'X-YouTube-Client-Version': args.clientVersion
                  },
                  body: JSON.stringify(payload)
                });
                return await r.text();
              } catch (e) {
                return '';
              }
            }
            """;

    /** Шаблон payload с плейсхолдером {@code __VISITOR__} для страничного заполнения.
     *  Payload template with a {@code __VISITOR__} placeholder for the page-side fill-in. */
    public static String payloadTemplate(String videoId) {
        return "{\"videoId\":\"" + videoId + "\",\"context\":{\"client\":{"
                + "\"clientName\":\"" + CLIENT_NAME + "\","
                + "\"clientVersion\":\"" + CLIENT_VERSION + "\","
                + "\"hl\":\"en\",\"gl\":\"US\","
                + "\"userAgent\":\"" + CLIENT_UA + "\","
                + "\"deviceMake\":\"Apple\",\"deviceModel\":\"RealityDevice17,1\","
                + "\"osName\":\"visionOS\",\"osVersion\":\"26.5.23O471\","
                + "\"visitorData\":\"__VISITOR__\"}},"
                + "\"contentCheckOk\":true,\"racyCheckOk\":true}";
    }

    static void logParsed(YouTubePlayer.Player player) {
        log.info("YouTube: {} ({} форматов, статус {})", player.videoId(),
                player.formats().size(), player.status());
    }
}
