package com.flynet.hlsgrabber.ui;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

import javax.swing.border.Border;

/** 1px rounded stroke that follows the active theme at paint time. */
final class ThemeBorder implements Border {

    private final int radius;

    ThemeBorder(int radius) {
        this.radius = radius;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(ThemeManager.current().cardBorder());
        g2.drawRoundRect(x, y, w - 1, h - 1, radius * 2, radius * 2);
        g2.dispose();
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(2, 2, 2, 2);
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }
}
