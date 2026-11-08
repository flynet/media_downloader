package com.flynet.hlsgrabber.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Window;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.UIManager;

/**
 * Theme registry and switching.
 *
 * <p>Delegates (GlassButtonUI, RoundTextFieldUI, RoundTextAreaUI, ...) read
 * {@link #current()} at paint time,
 * so a theme change repaints everything. {@link #setTheme} also walks every window and
 * calls updateUI() so UIManager-driven fonts/colors re-apply; components with a "role"
 * client property (title/titleAccent/secondary) get their label styles re-applied.
 */
public final class ThemeManager {

    private static Theme current = Theme.LIGHT;
    private static Consumer<String> saveHook = null;

    private ThemeManager() {}

    public static Theme current() {
        return current;
    }

    /** Wire persistence (called from App once Settings are loaded). */
    public static void setSaveHook(Consumer<String> hook) {
        saveHook = hook;
    }

    public static void init(String name) {
        current = Theme.byName(name);
        applyDefaults();
    }

    public static void toggle() {
        setTheme(current == Theme.LIGHT ? Theme.DARK : Theme.LIGHT);
        if (saveHook != null) {
            saveHook.accept(current == Theme.LIGHT ? "light" : "dark");
        }
    }

    public static void setTheme(Theme t) {
        current = t;
        applyDefaults();
        for (Window w : Window.getWindows()) {
            retheme(w);
            w.repaint();
        }
    }

    private static void applyDefaults() {
        Theme t = current;
        UIManager.put("control", t.windowBackground());
        UIManager.put("Panel.background", t.panelBackground());
        UIManager.put("Panel.foreground", t.textPrimary());

        UIManager.put("Label.foreground", t.textPrimary());
        UIManager.put("Label.font", t.baseFont());

        UIManager.put("ButtonUI", GlassButtonUI.class.getName());
        UIManager.put("ToggleButtonUI", GlassButtonUI.class.getName());
        UIManager.put("CheckBoxUI", GlassButtonUI.class.getName());
        UIManager.put("Button.font", t.baseFont());
        UIManager.put("Button.foreground", t.textPrimary());
        UIManager.put("Button.background", t.buttonFill());

        UIManager.put("TextFieldUI", RoundTextFieldUI.class.getName());
        UIManager.put("TextField.font", t.baseFont());
        UIManager.put("TextField.background", t.fieldBackground());
        UIManager.put("TextField.foreground", t.textPrimary());
        UIManager.put("TextField.insertionColor", t.textPrimary());
        UIManager.put("TextField.selectionBackground", t.selectionBackground());
        UIManager.put("TextField.selectionForeground", t.selectionText());

        UIManager.put("TextAreaUI", RoundTextAreaUI.class.getName());
        UIManager.put("TextArea.font", t.monoFont());
        UIManager.put("TextArea.background", t.cardBackground());
        UIManager.put("TextArea.foreground", t.textPrimary());
        UIManager.put("TextArea.insertionColor", t.textPrimary());
        UIManager.put("TextArea.selectionBackground", t.selectionBackground());
        UIManager.put("TextArea.selectionForeground", t.selectionText());
        UIManager.put("TextArea.margin", new Insets(8, 12, 8, 12));

        UIManager.put("List.background", t.cardBackground());
        UIManager.put("List.foreground", t.textPrimary());
        UIManager.put("List.font", t.baseFont());
        UIManager.put("List.selectionBackground", t.selectionBackground());
        UIManager.put("List.selectionForeground", t.selectionText());

        UIManager.put("ComboBoxUI", GlassComboUI.class.getName());
        UIManager.put("ComboBox.font", t.baseFont());
        UIManager.put("ComboBox.background", t.fieldBackground());
        UIManager.put("ComboBox.foreground", t.textPrimary());
        UIManager.put("ComboBox.selectionBackground", t.selectionBackground());
        UIManager.put("ComboBox.selectionForeground", t.selectionText());

        UIManager.put("ScrollBarUI", GlassScrollBarUI.class.getName());
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.background", t.panelBackground());
        UIManager.put("ScrollBar.foreground", t.scrollbarThumb());

        UIManager.put("SplitPaneUI", GlassSplitPaneUI.class.getName());
        UIManager.put("SplitPane.dividerSize", 11);
        UIManager.put("SplitPane.background", t.panelBackground());

        UIManager.put("ScrollPane.background", t.panelBackground());
        UIManager.put("Viewport.background", t.panelBackground());

        UIManager.put("OptionPane.background", t.panelBackground());
        UIManager.put("OptionPane.messageForeground", t.textPrimary());

        UIManager.put("ToolTip.background", t.cardBackground());
        UIManager.put("ToolTip.foreground", t.textPrimary());
        UIManager.put("ToolTip.font", t.baseFont());
        UIManager.put("ToolTip.border", new ThemeBorder(6));

        UIManager.put("ProgressBar.background", t.panelBackground());
        UIManager.put("ProgressBar.foreground", t.accent());
    }

    /** Walk a component tree: updateUI + re-apply label roles. */
    public static void retheme(Component root) {
        if (root instanceof JRootPane rp) {
            rp.getContentPane().setBackground(current.panelBackground());
        }
        if (root instanceof JComponent jc) {
            jc.updateUI();
            applyRoleStyles(jc);
        }
        if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                retheme(child);
            }
        }
        if (root instanceof JList<?> jl) {
            jl.updateUI();
        }
    }

    /** Register a window so a later theme toggle re-themes it (JFrame/JDialog). */
    public static void addWindow(Window w) {
        retheme(w);
    }

    static void applyRoleStyles(JComponent c) {
        Object role = c.getClientProperty("role");
        Theme t = current;
        if ("title".equals(role)) {
            c.setFont(t.titleFont());
            c.setForeground(t.textPrimary());
        } else if ("titleAccent".equals(role)) {
            c.setFont(t.titleFont());
            c.setForeground(t.accent());
        } else if ("secondary".equals(role)) {
            c.setFont(t.baseFont());
            c.setForeground(t.textSecondary());
        } else if ("accent".equals(role)) {
            c.setFont(t.baseFont());
            c.setForeground(t.accent());
        } else if ("mono".equals(role)) {
            c.setFont(t.monoFont());
            c.setForeground(t.textPrimary());
        } else if ("panel".equals(role)) {
            c.setBackground(t.panelBackground());
        }
    }
}
