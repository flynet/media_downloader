package com.flynet.hlsgrabber.ui;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;

/**
 * Flat rounded button: pill fill, no border for primary (accent), 1px stroke for secondary.
 * Also renders JCheckBox: rounded accent square with a stroked check glyph.
 * Colors follow the active theme at paint time.
 */
public final class GlassButtonUI extends BasicButtonUI {

    public static ComponentUI createUI(JComponent c) {
        return new GlassButtonUI();
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Theme t = ThemeManager.current();
        AbstractButton b = (AbstractButton) c;
        int w = c.getWidth();
        int h = c.getHeight();
        boolean enabled = c.isEnabled();

        if (b instanceof javax.swing.JCheckBox) {
            paintCheckBox(g2, b, t, w, h, enabled);
            g2.dispose();
            return;
        }

        int r = t.controlRadius();
        boolean primary = Boolean.TRUE.equals(c.getClientProperty("primary"));
        int pillH = Math.min(h, 34);
        int y0 = (h - pillH) / 2;
        Color fill;
        if (!enabled) {
            fill = new Color(t.buttonFill().getRed(), t.buttonFill().getGreen(), t.buttonFill().getBlue(), 120);
        } else if (primary) {
            fill = b.getModel().isPressed() ? t.accentPressed() : t.accent();
        } else {
            fill = b.getModel().isPressed() ? t.pressedFill()
                    : (b.getModel().isRollover() ? t.hoverFill() : t.buttonFill());
        }
        g2.setColor(fill);
        g2.fillRoundRect(0, y0, w - 1, pillH - 1, r * 2, r * 2);
        if (!primary && enabled) {
            g2.setColor(t.cardBorder());
            g2.drawRoundRect(0, y0, w - 1, pillH - 1, r * 2, r * 2);
        }

        String txt = b.getText();
        if (txt != null && !txt.isEmpty()) {
            FontMetrics fm = g2.getFontMetrics(b.getFont());
            int sw = fm.stringWidth(txt);
            int th = fm.getHeight();
            int tx = (w - sw) / 2;
            int ty = y0 + (pillH - th) / 2 + fm.getAscent();
            g2.setColor(!enabled ? t.textDisabled() : (primary ? t.accentText() : t.textPrimary()));
            g2.drawString(txt, tx, ty);
        }
        g2.dispose();
    }

    private void paintCheckBox(Graphics2D g2, AbstractButton b, Theme t, int w, int h, boolean enabled) {
        int s = 18;
        int cy = (h - s) / 2;
        boolean sel = b.getModel().isSelected();
        if (sel) {
            g2.setColor(enabled ? t.accent() : new Color(t.accent().getRed(), t.accent().getGreen(), t.accent().getBlue(), 120));
            g2.fillRoundRect(0, cy, s - 1, s - 1, 10, 10);
            g2.setColor(t.accentText());
            g2.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
            p.moveTo(4.5, cy + 9.5);
            p.lineTo(8, cy + 13);
            p.lineTo(13.5, cy + 5);
            g2.draw(p);
        } else {
            g2.setColor(enabled ? t.fieldBackground() : new Color(t.fieldBackground().getRed(), t.fieldBackground().getGreen(), t.fieldBackground().getBlue(), 120));
            g2.fillRoundRect(0, cy, s - 1, s - 1, 10, 10);
            g2.setColor(t.cardBorder());
            g2.drawRoundRect(0, cy, s - 1, s - 1, 10, 10);
        }
        String txt = b.getText();
        if (txt != null && !txt.isEmpty()) {
            FontMetrics fm = g2.getFontMetrics(b.getFont());
            int tx = s + 8;
            int th = fm.getHeight();
            int ty = (h - th) / 2 + fm.getAscent();
            g2.setColor(enabled ? t.textPrimary() : t.textDisabled());
            g2.drawString(txt, tx, ty);
        }
    }
}
