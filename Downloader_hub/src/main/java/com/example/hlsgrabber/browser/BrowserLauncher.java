package com.example.hlsgrabber.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Браузер с headed persistent-контекстом (TZ 5.2.1): папка профиля {@code <downloads>/.profile},
 * порядок каналов: chrome → msedge → бандлированный Chromium (предупреждение в логе, TZ R3).
 * Анти-детект аргументы; UA-override только для фолбэка бандлированного Chromium.
 * Headed persistent-context browser (TZ 5.2.1): profile dir {@code <downloads>/.profile},
 * channel order chrome → msedge → bundled Chromium (with a warning in the log, TZ R3).
 * Anti-detect args, UA override only for the bundled Chromium fallback.
 */
public final class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final Playwright playwright;
    private BrowserContext ctx;
    private String channelName;

    /**
     * @param profileDir папка персистентного профиля (создаётся, если нет) /
     *                   persistent profile directory (created if missing)
     * @param userAgent  UA-override для фолбэка бандлированного Chromium; null для
     *                   системных каналов / UA override for the bundled Chromium
     *                   fallback; null for system channels
     * @param headless   true только для --smoke харнесса (R3: headed — режим продукта) /
     *                   true only for the --smoke harness (R3: headed is the product mode)
     */
    public BrowserLauncher(Path profileDir, String userAgent, boolean headless) {
        // App-image (TZ R3): бандлированные браузеры поставлены внутрь пакета в app\browsers.
        // Драйвер читает PLAYWRIGHT_BROWSERS_PATH из окружения; launcher exe
        // наследует окружение ОС, поэтому путь инжектируется через CreateOptions.
        // App-image (TZ R3): bundled browsers shipped inside the package at app\browsers.
        // The driver reads PLAYWRIGHT_BROWSERS_PATH from its environment; the launcher exe
        // inherits the OS env, so the path is injected via CreateOptions.
        Path browsers = AppPaths.bundledBrowsersDir();
        Playwright.CreateOptions co = new Playwright.CreateOptions();
        if (browsers != null) {
            co.setEnv(Map.of("PLAYWRIGHT_BROWSERS_PATH", browsers.toString()));
            log.info("Бандлированные браузеры / bundled browsers: {}", browsers);
        }
        playwright = Playwright.create(co);
        BrowserType bt = playwright.chromium();

        BrowserType.LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions();
        opts.setHeadless(headless);
        opts.setViewportSize(1280, 720);
        opts.setIgnoreHTTPSErrors(true);
        opts.setArgs(List.of(
                "--disable-blink-features=AutomationControlled",
                "--disable-popup-blocking",
                // probe5: video.play() через evaluate требует user activation при
                // user-gesture-required; no-user-gesture-required разрешает харнессам
                // автовоспроизведение
                // probe5: video.play() via evaluate needs user activation under
                // user-gesture-required; no-user-gesture-required lets harnesses autoplay
                "--autoplay-policy=no-user-gesture-required",
                "--disable-infobars"));

        String chosen = null;
        if (!headless) {
            for (String ch : new String[]{"chrome", "msedge"}) {
                opts.setChannel((String) ch);
                try {
                    ctx = bt.launchPersistentContext(profileDir, opts);
                    chosen = ch;
                    log.info("Браузер: системный / system browser {} (профиль / profile: {})", ch, profileDir);
                    break;
                } catch (Exception e) {
                    log.warn("Канал {} недоступен / channel {} unavailable: {}", ch, e.getMessage());
                }
            }
        }
        if (chosen == null) {
            opts.setChannel((String) null);
            if (!headless) {
                opts.setUserAgent(userAgent);
                log.warn("Chrome и Edge не найдены — bundled Chromium с UA-override (анти-детект слабее) / Chrome and Edge not found — bundled Chromium with UA override (anti-detect is weaker)");
            }
            ctx = bt.launchPersistentContext(profileDir, opts);
            channelName = headless ? "chromium-headless" : "chromium-bundled";
        } else {
            channelName = chosen;
        }
        // Чистый старт GUI: persistent-профиль восстанавливает вкладки прошлых сессий
        // (probe-харнесс, прежние страницы пользователя). Закрываем их, чтобы окно
        // показывало одну чистую страницу и сниффер следил только за тем, что открывает
        // пользователь. ПОРЯДОК ВАЖЕН для headful: закрытие ПОСЛЕДНЕЙ вкладки headful-окна
        // убивает окно, и тогда ctx.newPage() падает с
        // "Target.createTarget: Failed to open a new tab" (наблюдалось в E2e харнессе).
        // Поэтому снимаем список восстанавливаемых вкладок, открываем свою свежую
        // страницу сначала, закрываем старые.
        // Clean GUI start: a persistent profile restores the tabs of previous sessions
        // (probe harnesses, earlier user pages). Close them so the window shows one
        // clean page and the sniffer watches only what the user opens.
        // ORDER MATTERS for headful: closing the LAST tab of a headful window kills
        // the window, and then ctx.newPage() fails with
        // "Target.createTarget: Failed to open a new tab" (observed in E2e harness).
        // So snapshot the restore tabs, open our fresh page first, close the old ones.
        List<Page> restore = ctx.pages();
        ctx.newPage();
        int closed = 0;
        for (Page p : restore) {
            if (p.isClosed()) {
                continue;
            }
            try {
                p.close();
                closed++;
            } catch (Exception e) {
                log.debug("restore tab close failed: {}", e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("Закрыто вкладок из прошлой сессии / closed tabs from previous session: {}", closed);
        }
    }

    public BrowserContext context() {
        return ctx;
    }

    public String channel() {
        return channelName;
    }

    public String userAgent() {
        // UA, наблюдаемый на первой странице (дефолт браузера или override)
        // UA observed on the first page (browser default or override)
        try {
            Page p = ctx.pages().isEmpty() ? null : ctx.pages().get(0);
            return p == null ? null : p.evaluate("() => navigator.userAgent").toString();
        } catch (Exception e) {
            return null;
        }
    }

    public void openPage(String url) {
        Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
        log.info("Открываю / opening: {}", url);
        try {
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (Exception e) {
            // диспетчер гоняет со штормом запросов первой навигации (известная
            // Playwright Java гонка: "Object doesn't exist: response@..."). Навигация
            // уже отправлена; страница продолжает грузиться. Сниффинг продолжается.
            // dispatcher races with the request storm of the first navigation (known Playwright
            // Java race: "Object doesn't exist: response@..."). Navigation is already sent;
            // the page keeps loading. Sniffing continues.
            log.warn("Навигация / navigation: {}: {} (страница продолжает грузиться / the page keeps loading)", url, e.getMessage());
        }
        String title = null;
        try {
            title = page.title();
        } catch (Exception e) {
            log.debug("title after navigate: {}", e.getMessage());
        }
        String shown = title == null || title.isBlank() ? "" : " — " + title;
        log.info("Страница открыта / page opened: {}{}", page.url(), shown);
    }

    /** Активная страница persistent-контекста (для харнессов и UI).
     * The active page of the persistent context (for harnesses and UI). */
    public Page page() {
        return ctx.pages().isEmpty() ? null : ctx.pages().get(0);
    }

    /** Cookie-заголовок для заголовков fetcher (TZ 6.1): cookies источника страницы.
     * Cookie header for fetcher headers (TZ 6.1): cookies of the page origin. */
    public String cookieHeader(String pageUrl) {
        try {
            List<Cookie> cookies = ctx.cookies(pageUrl);
            StringBuilder sb = new StringBuilder();
            for (Cookie c : cookies) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(c.name).append('=').append(c.value);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public void close() {
        try {
            ctx.close();
        } catch (Exception e) {
            log.debug("browser close: {}", e.getMessage());
        }
        try {
            playwright.close();
        } catch (Exception e) {
            log.debug("playwright close: {}", e.getMessage());
        }
    }
}
