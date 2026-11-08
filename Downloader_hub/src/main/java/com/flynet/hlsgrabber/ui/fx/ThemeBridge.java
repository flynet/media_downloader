package com.flynet.hlsgrabber.ui.fx;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * JavaFX theme bridge: AtlantaFX Cupertino (Apple) themes + the app's
 * "Apple glass" palette + Inter typography, in light and dark variants.
 *
 * <p>Roles: styling is driven by inline styles (the strongest CSS layer,
 * which wins over AtlantaFX theme CSS). Every styled node registers a
 * style function; a theme switch re-applies all registered functions,
 * so live toggle repaints the whole window (cards, rows, log, header).
 *
 * <p>Colors mirror the Swing Theme palette so the FX UI keeps the exact
 * look already validated: light panel #F5F5F7, card #FFFFFF, border
 * #DFE2E9, Apple accent #007AFF; dark bg #1C1C1E, card #2C2C2E, border
 * #48484E, dark accent #0A84FF; Apple semantic green/red/gray statuses.
 * Must be initialized on the FX app thread.
 */
public final class ThemeBridge {

    public enum Mode { LIGHT, DARK }

    /** Hex token palette for a theme variant. */
    public record Palette(String panel, String card, String cardBorder, String field,
                          String textPrimary, String textSecondary, String accent,
                          String statusDone, String statusError, String statusCancelled,
                          int cardRadius) {}

    public static final Palette LIGHT = new Palette(
            "#F5F5F7", "#FFFFFF", "#DFE2E9", "#FFFFFF",
            "#1C1C1E", "#6E7075", "#007AFF",
            "#34C759", "#D72E22", "#8E9095", 12);

    public static final Palette DARK = new Palette(
            "#1C1C1E", "#2C2C2E", "#48484E", "#1C1C1E",
            "#EBEBED", "#98999E", "#0A84FF",
            "#30D158", "#E4463C", "#6E6F74", 12);

    private static Mode mode = Mode.LIGHT;
    private static Font baseFont;
    private static Font titleFont;
    private static Font monoFont;
    private static final Map<Node, Function<Palette, String>> registry = new ConcurrentHashMap<>();
    private static Consumer<String> saveHook;

    private ThemeBridge() {}

    public static Mode mode() {
        return mode;
    }

    public static Palette palette(Mode m) {
        return m == Mode.LIGHT ? LIGHT : DARK;
    }

    public static int cardRadius() {
        return palette(mode).cardRadius();
    }

    public static Font baseFont() {
        return baseFont;
    }

    public static Font titleFont() {
        return titleFont;
    }

    public static Font monoFont() {
        return monoFont;
    }

    /** Register a node with a palette style function; applies immediately. */
    public static void register(Node node, Function<Palette, String> styler) {
        registry.put(node, styler);
        node.setStyle(styler.apply(palette(mode)));
    }

    /** Unregister a node (rows dropped from the list). */
    public static void unregister(Node node) {
        registry.remove(node);
    }

    /** Role style: typography + text fill for a semantic role. */
    public static String roleStyle(String role, Palette p) {
        return switch (role) {
            case "title" -> "-fx-font-family: \"Inter SemiBold\", \"Inter\", \"Segoe UI\"; -fx-font-size: 14px; -fx-text-fill: " + p.textPrimary();
            case "titleAccent" -> "-fx-font-family: \"Inter SemiBold\", \"Inter\", \"Segoe UI\"; -fx-font-size: 14px; -fx-text-fill: " + p.accent();
            case "secondary" -> "-fx-font-family: \"Inter Medium\", \"Inter\", \"Segoe UI\"; -fx-font-size: 11px; -fx-text-fill: " + p.textSecondary();
            case "accent" -> "-fx-font-family: \"Inter Medium\", \"Inter\", \"Segoe UI\"; -fx-font-size: 12px; -fx-text-fill: " + p.accent();
            case "mono" -> monoFontStyle(p.textPrimary());
            case "monoAccent" -> monoFontStyle(p.accent());
            case "monoDone" -> monoFontStyle(p.statusDone());
            case "monoError" -> monoFontStyle(p.statusError());
            case "panel" -> "-fx-background-color: " + p.panel();
            default -> "";
        };
    }

    private static String monoFontStyle(String color) {
        return "-fx-font-family: \"Consolas\", \"Courier New\", monospace; -fx-font-size: 12px; -fx-text-fill: " + color;
    }

    /** Role registration: inline style beats AtlantaFX CSS, re-applied on toggle. */
    public static void applyRole(Node node, String role) {
        register(node, m -> roleStyle(role, m));
    }

    /** Install the app; pass the saved theme name ("light"/"dark"). */
    public static void init(String saved) {
        Mode m = saved.equalsIgnoreCase("dark") ? Mode.DARK : Mode.LIGHT;
        baseFont = loadInter("fonts/Inter-Regular.ttf", 13);
        titleFont = loadInter("fonts/Inter-SemiBold.ttf", 14);
        monoFont = Font.font("Consolas", 12);
        Application.setUserAgentStylesheet(m == Mode.LIGHT
                ? new CupertinoLight().getUserAgentStylesheet()
                : new CupertinoDark().getUserAgentStylesheet());
        mode = m;
        Platform.runLater(() -> registry.forEach((node, f) -> node.setStyle(f.apply(palette(m)))));
    }

    /** Toggle theme, persist via save hook, re-apply all styles. Returns new mode. */
    public static Mode toggle() {
        return setTheme(mode == Mode.LIGHT ? Mode.DARK : Mode.LIGHT);
    }

    /** Set theme explicitly; returns the applied mode. */
    public static Mode setTheme(Mode m) {
        Application.setUserAgentStylesheet(m == Mode.LIGHT
                ? new CupertinoLight().getUserAgentStylesheet()
                : new CupertinoDark().getUserAgentStylesheet());
        mode = m;
        registry.forEach((node, f) -> node.setStyle(f.apply(palette(m))));
        if (saveHook != null) saveHook.accept(m == Mode.LIGHT ? "light" : "dark");
        return m;
    }

    /** Wire persistence (settings save on theme change). */
    public static void setSaveHook(Consumer<String> hook) {
        saveHook = hook;
    }

    /** RGB of a hex token, for pixel harnesses: "007AFF" -> {0,122,255}. */
    public static int[] rgb(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return new int[]{r, g, b};
    }

    private static Font loadInter(String resource, double size) {
        try (InputStream in = ThemeBridge.class.getResourceAsStream("/" + resource)) {
            if (in != null) {
                Font loaded = Font.loadFont(in, size);
                if (loaded != null) return loaded;
            }
        } catch (Exception e) {
            System.err.println("font load failed: " + resource + ": " + e);
        }
        return Font.font("Segoe UI", size);
    }
}
