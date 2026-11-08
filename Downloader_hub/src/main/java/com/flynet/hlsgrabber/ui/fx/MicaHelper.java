package com.flynet.hlsgrabber.ui.fx;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import javafx.stage.Stage;

/**
 * Native title bar + Mica backdrop via DWM (Windows 11 only, no client-side
 * decorations). The window title is static ("HLS Grabber") so the HWND can be
 * found by title with FindWindow; the download folder lives in the header label.
 *
 * <p>Probed on this build (FxProbe, Oct 2026): attr 20 (immersive dark mode)
 * returns 0, attr 38 (system backdrop type) returns 0, attr 102 (legacy
 * backdrop) returns E_INVALIDARG — so 38 is the main path, 102 best-effort.
 * Nothing here ever throws; a failure leaves the stock title bar.
 */
public final class MicaHelper {

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE_LEGACY = 102;
    private static final int DWMSBT_MAINWINDOW = 2;

    private interface DwmApi extends com.sun.jna.Library {
        int DwmSetWindowAttribute(HWND hwnd, int attr, IntByReference pv, int cb);
    }

    private MicaHelper() {}

    /** Apply Mica + dark/light title bar to the shown stage. */
    public static void apply(Stage stage, boolean dark) {
        if (!stage.isShowing()) {
            return;
        }
        try {
            HWND hwnd = User32.INSTANCE.FindWindow(null, stage.getTitle());
            if (hwnd == null) {
                return;
            }
            DwmApi dwm = Native.load("dwmapi", DwmApi.class);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                    new IntByReference(dark ? 1 : 0), 4);
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE,
                    new IntByReference(DWMSBT_MAINWINDOW), 4);
            try {
                dwm.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE_LEGACY,
                        new IntByReference(DWMSBT_MAINWINDOW), 4);
            } catch (Throwable ignored) {
                // E_INVALIDARG on newer builds — expected, best-effort
            }
        } catch (Throwable ignored) {
            // non-Windows / missing dwmapi — stock title bar is fine
        }
    }
}
