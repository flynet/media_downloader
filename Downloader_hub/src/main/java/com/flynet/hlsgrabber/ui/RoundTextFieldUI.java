package com.flynet.hlsgrabber.ui;

import java.awt.Graphics;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextFieldUI;

/**
 * Themed rounded JTextField delegate. Extends BasicTextFieldUI, NOT
 * BasicTextUI directly: BasicTextUI's view factory {@code create(Element)}
 * returns null, which leaves the view hierarchy empty, and the final
 * {@code paint()} guard ({@code rootView.getViewCount() > 0}) then skips all
 * painting, so a {@code paintBackground} override would never run.
 * BasicTextFieldUI supplies the real view factory (LabelView/ParagraphView/
 * BoxView) so text is laid out and painted. The rounded fill is drawn in
 * {@code paintBackground}, the rounded 1px stroke by {@link ThemeBorder}.
 */
public final class RoundTextFieldUI extends BasicTextFieldUI {

    public static ComponentUI createUI(JComponent c) {
        return new RoundTextFieldUI();
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
