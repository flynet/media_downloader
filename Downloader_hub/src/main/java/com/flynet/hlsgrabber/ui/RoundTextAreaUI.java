package com.flynet.hlsgrabber.ui;

import java.awt.Graphics;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextAreaUI;

/**
 * Themed rounded JTextArea delegate. Extends BasicTextAreaUI, NOT BasicTextUI
 * directly (see {@link RoundTextFieldUI} for the reason: BasicTextUI's own
 * view factory returns null, so the final {@code paint()} guard skips all
 * painting). BasicTextAreaUI supplies the real view factory
 * (PlainView / WrappedPlainView) so the log text is laid out and painted.
 * The rounded fill is drawn in {@code paintBackground}, the rounded 1px
 * stroke by {@link ThemeBorder}.
 */
public final class RoundTextAreaUI extends BasicTextAreaUI {

    public static ComponentUI createUI(JComponent c) {
        return new RoundTextAreaUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        c.setBorder(new ThemeBorder(ThemeManager.current().controlRadius()));
    }

    @Override
    public void uninstallUI(JComponent c) {
        c.setBorder(null);
        super.uninstallUI(c);
    }

    @Override
    protected void paintBackground(Graphics g) {
        RoundFieldPaint.fillRound(g, getComponent());
    }
}
