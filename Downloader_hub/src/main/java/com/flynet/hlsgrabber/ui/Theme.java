package com.flynet.hlsgrabber.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * Flat "Apple Glass" palette: soft rounded cards, subtle shadows, accent highlights.
 * Two variants: light and dark. All UI delegates read the active palette from ThemeManager.
 */
public record Theme(
        Color windowBackground,
        Color panelBackground,
        Color cardBackground,
        Color cardBorder,
        Color fieldBackground,
        Color buttonFill,
        Color textPrimary,
        Color textSecondary,
        Color textDisabled,
        Color accent,
        Color accentPressed,
        Color accentText,
        Color accentSoft,
        Color hoverFill,
        Color pressedFill,
        Color selectionBackground,
        Color selectionText,
        Color scrollbarThumb,
        Color statusDownloading,
        Color statusDone,
        Color statusError,
        Color statusCancelled,
        Color shadow,
        Font baseFont,
        Font titleFont,
        Font monoFont,
        int cardRadius,
        int controlRadius,
        int rowHeight) {

    public static final Theme LIGHT = new Theme(
            new Color(245, 245, 247),
            new Color(245, 245, 247),
            Color.WHITE,
            new Color(223, 226, 233),
            Color.WHITE,
            new Color(236, 237, 241),
            new Color(28, 28, 30),
            new Color(110, 112, 117),
            new Color(174, 176, 181),
            new Color(0, 122, 255),
            new Color(0, 103, 216),
            Color.WHITE,
            new Color(0, 122, 255, 26),
            new Color(242, 243, 246),
            new Color(222, 224, 229),
            new Color(184, 212, 250),
            new Color(28, 28, 30),
            new Color(198, 199, 204),
            new Color(0, 122, 255),
            new Color(52, 199, 89),
            new Color(215, 46, 34),
            new Color(142, 144, 149),
            new Color(96, 98, 106, 26),
            new Font("Segoe UI", Font.PLAIN, 13),
            new Font("Segoe UI", Font.BOLD, 14),
            new Font("Consolas", Font.PLAIN, 12),
            12, 9, 88);

    public static final Theme DARK = new Theme(
            new Color(28, 28, 30),
            new Color(28, 28, 30),
            new Color(44, 44, 46),
            new Color(72, 72, 78),
            new Color(44, 44, 46),
            new Color(62, 62, 66),
            new Color(235, 235, 237),
            new Color(152, 153, 158),
            new Color(110, 111, 116),
            new Color(10, 132, 255),
            new Color(9, 113, 216),
            Color.WHITE,
            new Color(10, 132, 255, 40),
            new Color(255, 255, 255, 24),
            new Color(255, 255, 255, 46),
            new Color(44, 94, 168),
            new Color(235, 235, 237),
            new Color(98, 99, 104),
            new Color(10, 132, 255),
            new Color(48, 209, 88),
            new Color(228, 70, 60),
            new Color(110, 111, 116),
            new Color(0, 0, 0, 40),
            new Font("Segoe UI", Font.PLAIN, 13),
            new Font("Segoe UI", Font.BOLD, 14),
            new Font("Consolas", Font.PLAIN, 12),
            12, 9, 88);

    public static Theme byName(String name) {
        return "dark".equalsIgnoreCase(name) ? DARK : LIGHT;
    }
}
