package com.flynet.hlsgrabber.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicComboBoxUI;

/** Flat rounded combo box: field-fill body, chevron arrow, theme-aware text. */
public class GlassComboUI extends BasicComboBoxUI {

    public static ComponentUI createUI(JComponent c) {
        return new GlassComboUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        c.setOpaque(false);
        c.setPreferredSize(new Dimension(140, 34));
    }

    @Override
    protected JButton createArrowButton() {
        JButton b = new JButton();
        b.setUI(new NoPaintButtonUI());
        b.setBorder(null);
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(24, 34));
        return b;
    }

    @Override
    public void paint(Graphics g0, JComponent c) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme t = ThemeManager.current();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = c.getWidth();
        int h = c.getHeight();
        int pillH = Math.min(h, 34);
        int y = (h - pillH) / 2;
        int r = t.controlRadius();
        g.setColor(c.isEnabled() ? t.fieldBackground() : t.cardBackground());
        g.fillRoundRect(1, y, w - 2, pillH - 2, r * 2, r * 2);
        g.setColor(c.isEnabled() ? t.cardBorder() : t.textDisabled());
        g.drawRoundRect(1, y, w - 2, pillH - 2, r * 2, r * 2);
        // chevron
        int cx = w - 19;
        int cy = h / 2;
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c.isEnabled() ? t.textSecondary() : t.textDisabled());
        Path2D p = new Path2D.Float();
        p.moveTo(cx - 3.5f, cy - 4);
        p.lineTo(cx + 3, cy);
        p.lineTo(cx - 3.5f, cy + 4);
        g.draw(p);
        g.dispose();
        super.paint(g0, c);
    }
}
