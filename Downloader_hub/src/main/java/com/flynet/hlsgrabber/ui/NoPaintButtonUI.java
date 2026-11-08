package com.flynet.hlsgrabber.ui;

import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;

/** Invisible 0-size button used for combo arrow and scrollbar arrows. */
final class NoPaintButtonUI extends BasicButtonUI {

    public static ComponentUI createUI(JComponent c) {
        return new NoPaintButtonUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        AbstractButton b = (AbstractButton) c;
        b.setFocusable(false);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorder(null);
        b.setPreferredSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        // nothing
    }
}
