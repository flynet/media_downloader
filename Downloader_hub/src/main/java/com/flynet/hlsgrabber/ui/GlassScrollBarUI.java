package com.flynet.hlsgrabber.ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Slim scrollbar: no arrow buttons, transparent track, rounded thumb. */
public final class GlassScrollBarUI extends BasicScrollBarUI {

    public static ComponentUI createUI(JComponent c) {
        return new GlassScrollBarUI();
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        JButton b = new JButton();
        b.setUI(new NoPaintButtonUI());
        return b;
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        JButton b = new JButton();
        b.setUI(new NoPaintButtonUI());
        return b;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(ThemeManager.current().panelBackground());
        g2.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
        g2.dispose();
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        if (thumbBounds.isEmpty() || !c.isEnabled()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(ThemeManager.current().scrollbarThumb());
        int x = thumbBounds.x + 2;
        int y = thumbBounds.y + 2;
        int w = thumbBounds.width - 4;
        int h = thumbBounds.height - 4;
        g2.fillRoundRect(x, y, w, h, 12, 12);
        g2.dispose();
    }
}
