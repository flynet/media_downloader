package com.flynet.hlsgrabber.ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.text.JTextComponent;

/**
 * Shared rounded fill for the themed text delegates (RoundTextFieldUI,
 * RoundTextAreaUI). Called from paintBackground, which BasicTextUI invokes
 * from paintSafely when the component is opaque. The rounded rect fills the
 * component's bounds; the corner pixels outside the rounded corners show the
 * parent panel background painted before the child.
 */
final class RoundFieldPaint {

    private RoundFieldPaint() {
    }

    static void fillRound(Graphics g, JTextComponent tc) {
        Theme t = ThemeManager.current();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = tc.getWidth();
        int h = tc.getHeight();
        int r = t.controlRadius();
        g2.setColor(t.fieldBackground());
        g2.fillRoundRect(0, 0, w, h, r * 2, r * 2);
        g2.dispose();
    }
}
