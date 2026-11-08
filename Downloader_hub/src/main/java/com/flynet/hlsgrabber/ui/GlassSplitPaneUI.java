package com.flynet.hlsgrabber.ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/** Flat split pane: thin flat divider with a small accent pill. */
public final class GlassSplitPaneUI extends BasicSplitPaneUI {

    public static ComponentUI createUI(JComponent c) {
        return new GlassSplitPaneUI();
    }

    @Override
    public BasicSplitPaneDivider createDefaultDivider() {
        return new GlassDivider(this);
    }

    static final class GlassDivider extends BasicSplitPaneDivider {

        GlassDivider(BasicSplitPaneUI ui) {
            super(ui);
            setBorder(null);
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Theme t = ThemeManager.current();
            g2.setColor(t.panelBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g2.setColor(t.accent());
            g2.fillRoundRect(cx - 22, cy - 3, 44, 6, 6, 6);
            g2.dispose();
        }
    }
}
