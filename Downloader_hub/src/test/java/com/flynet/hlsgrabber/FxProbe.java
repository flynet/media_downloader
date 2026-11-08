package com.flynet.hlsgrabber;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JavaFX probe harness for the migration study (separate main class; App/Swing UI untouched).
 *
 * Verifies on this machine: JavaFX 21 in classpath mode, AtlantaFX 2.1.0 Cupertino
 * light/dark themes (runtime switch), Inter font registration + CSS pickup,
 * DWM dark titlebar / Mica effects. Prints pixel evidence for both themes.
 *
 * Launch pattern: FxProbe itself does NOT extend Application. In classpath mode the
 * JDK launcher intercepts an Application-derived main class and aborts with
 * "JavaFX runtime components are missing"; the documented workaround is a plain
 * launcher main which calls Application.launch on the Application subclass.
 *
 * Run from the fat jar (classpath mode, JavaFX natives bundled inside):
 *   java -cp target\media_downloader.jar com.flynet.hlsgrabber.FxProbe
 *
 * Markers on stdout: PROBE_FONT, PROBE_SNAPSHOT_SIZE, PROBE_THEME_LIGHT, PROBE_THEME_DARK,
 * PROBE_DWM, PROBE_CHECK, PROBE: PASS/FAIL. Exit code 0 when the probe finishes.
 */
public class FxProbe {

    /** Plain launcher: no Application inheritance, so the JDK launcher does not intercept. */
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }

    public static class App extends Application {

        private static final String TITLE = "FxProbe";

        private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
        private static final int DWMWA_MICA_EFFECT = 102;
        private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
        private static final int DWMSBT_MAINWINDOW = 2;

        private boolean fontLoaded;
        private boolean lightPass;
        private boolean darkPass;

        /** DWM API is not in jna-platform 5.15.0, so declare dwmapi.dll directly. */
        public interface DwmApiLib extends Library {
            DwmApiLib INSTANCE = Native.load("dwmapi", DwmApiLib.class);
            int DwmSetWindowAttribute(HWND hwnd, int attr, Pointer pv, int cb);
        }

        @Override
        public void start(Stage stage) {
            try {
                fontLoaded = loadFonts();

                Label label = new Label("FxProbe label, Inter: abcdefg ABC 0123456789");
                TextField field = new TextField("text field: abcdefg");
                Button button = new Button("Accent button");
                button.setDefaultButton(true);
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().addAll("Cupertino", "Light", "Dark");
                combo.setValue("Cupertino");

                VBox root = new VBox(10, label, field, button, combo);
                root.setAlignment(Pos.TOP_LEFT);
                root.setStyle("-fx-padding: 24; -fx-font-family: \"Inter Regular\", \"Inter\";");

                Scene scene = new Scene(root, 760, 480);
                Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
                stage.setTitle(TITLE);
                stage.initStyle(StageStyle.DECORATED);
                stage.setScene(scene);
                stage.show();

                // window visible; snapshot after layout + rendering settle
                Platform.runLater(() ->
                        new Timeline(new KeyFrame(Duration.millis(2000), e -> lightPhase(scene))).play());
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                System.out.println("PROBE: FAIL (start)");
                System.out.flush();
                System.exit(1);
            }
        }

        private boolean loadFonts() {
            String[] paths = {"/fonts/Inter-Regular.ttf", "/fonts/Inter-Medium.ttf", "/fonts/Inter-SemiBold.ttf"};
            int ok = 0;
            for (String path : paths) {
                try (InputStream in = App.class.getResourceAsStream(path)) {
                    if (in == null) {
                        System.out.println("PROBE_FONT " + path + " MISSING");
                        continue;
                    }
                    Font font = Font.loadFont(in, 14);
                    if (font == null) {
                        System.out.println("PROBE_FONT " + path + " LOAD_FAILED");
                        continue;
                    }
                    System.out.println("PROBE_FONT loaded " + font.getFamily() + "/" + font.getName() + " from " + path);
                    ok++;
                } catch (Exception e) {
                    System.out.println("PROBE_FONT " + path + " ERROR " + e);
                }
            }
            List<String> families = Font.getFamilies().stream()
                    .filter(f -> f.startsWith("Inter"))
                    .sorted()
                    .collect(Collectors.toList());
            System.out.println("PROBE_FONT families=" + families + " loaded=" + ok + " of " + paths.length);
            return ok == paths.length;
        }

        private void lightPhase(Scene scene) {
            int w = (int) scene.getWidth();
            int h = (int) scene.getHeight();
            System.out.println("PROBE_WINDOW title=" + TITLE + " scene=" + w + "x" + h);
            System.out.println("PROBE_SNAPSHOT_SIZE light=" + w + "x" + h);
            lightPass = printPixels("PROBE_THEME_LIGHT", scene);

            System.out.println("PROBE_SWITCH theme=DARK");
            Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
            new Timeline(new KeyFrame(Duration.millis(1200), e -> darkPhase(scene))).play();
        }

        private void darkPhase(Scene scene) {
            int w = (int) scene.getWidth();
            int h = (int) scene.getHeight();
            System.out.println("PROBE_SNAPSHOT_SIZE dark=" + w + "x" + h);
            darkPass = printPixels("PROBE_THEME_DARK", scene);

            dwm();

            boolean sizeOk = w > 0 && h > 0;
            boolean pass = fontLoaded && lightPass && darkPass && sizeOk;
            System.out.println("PROBE_CHECK font=" + fontLoaded + " light_bg=" + lightPass
                    + " dark_bg=" + darkPass + " size=" + sizeOk);
            System.out.println(pass ? "PROBE: PASS" : "PROBE: FAIL");
            System.out.flush();
            Platform.exit();
            System.exit(0);
        }

        /**
         * Snapshot the live scene (PIX_ARGB) and print >=6 pixel samples:
         * root background corners, label glyph pixel, text field fill,
         * button fill + accent pixel, combo box fill. Returns true when the root
         * background matches the theme (light: >=244 channels, dark: <=34).
         */
        private boolean printPixels(String tag, Scene scene) {
            // Scene exposes only async snapshot(Callback,...); Node/SnapshotParameters
            // path: snapshot the root node synchronously.
            WritableImage image = scene.getRoot().snapshot(new SnapshotParameters(),
                    new WritableImage((int) scene.getWidth(), (int) scene.getHeight()));
            PixelReader r = image.getPixelReader();
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();

            List<Node> kids = ((Pane) scene.getRoot()).getChildren();
            Label label = (Label) kids.get(0);
            TextField field = (TextField) kids.get(1);
            Button button = (Button) kids.get(2);
            ComboBox<?> combo = (ComboBox<?>) kids.get(3);

            System.out.println(tag + " size=" + w + "x" + h);
            String bg = px(r, 2, 2);
            System.out.println(tag + " root_bg(2,2)=" + bg);
            System.out.println(tag + " root_bg(" + (w - 2) + "," + (h - 2) + ")=" + px(r, w - 2, h - 2));
            System.out.println(tag + " label_font=" + label.getFont().getFamily() + "/" + label.getFont().getName());

            int[] glyph = scanDarkest(r, w, h, label);
            System.out.println(tag + " label_text(" + glyph[0] + "," + glyph[1] + ")=" + px(r, glyph[0], glyph[1]));

            int fx = (int) (field.getLayoutX() + field.getWidth() / 2);
            int fy = (int) (field.getLayoutY() + field.getHeight() / 2);
            System.out.println(tag + " textfield_center(" + fx + "," + fy + ")=" + px(r, fx, fy));

            int bx = (int) (button.getLayoutX() + 4);
            int by = (int) (button.getLayoutY() + button.getHeight() / 2);
            System.out.println(tag + " button_fill(" + bx + "," + by + ")=" + px(r, bx, by));
            int[] accent = scanMostBlue(r, w, h, button);
            System.out.println(tag + " button_accent(" + accent[0] + "," + accent[1] + ")=" + px(r, accent[0], accent[1]));

            int cx = (int) (combo.getLayoutX() + combo.getWidth() / 2);
            int cy = (int) (combo.getLayoutY() + combo.getHeight() / 2);
            System.out.println(tag + " combo_center(" + cx + "," + cy + ")=" + px(r, cx, cy));

            int[] rgb = parseRgb(bg);
            if ("PROBE_THEME_LIGHT".equals(tag)) {
                boolean ok = rgb[0] >= 244 && rgb[1] >= 244 && rgb[2] >= 244;
                System.out.println(tag + "_BG_LIGHT=" + ok + " expected=light(>=244) observed=" + bg);
                return ok;
            }
            boolean ok = rgb[0] <= 34 && rgb[1] <= 34 && rgb[2] <= 34;
            System.out.println(tag + "_BG_DARK=" + ok + " expected=dark(<=34) observed=" + bg);
            return ok;
        }

        private int[] scanDarkest(PixelReader r, int w, int h, Node node) {
            int x0 = Math.max(0, (int) node.getLayoutX());
            int y0 = Math.max(0, (int) node.getLayoutY());
            int x1 = Math.min(w - 1, (int) (x0 + node.getLayoutBounds().getWidth()));
            int y1 = Math.min(h - 1, (int) (y0 + node.getLayoutBounds().getHeight()));
            int best = 0;
            int bestSum = Integer.MAX_VALUE;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int[] rgb = argbToRgb(r.getArgb(x, y));
                    int sum = rgb[0] + rgb[1] + rgb[2];
                    if (sum < bestSum) {
                        bestSum = sum;
                        best = x * 100000 + y;
                    }
                }
            }
            return new int[]{best / 100000, best % 100000};
        }

        private int[] scanMostBlue(PixelReader r, int w, int h, Node node) {
            int x0 = Math.max(0, (int) node.getLayoutX());
            int y0 = Math.max(0, (int) node.getLayoutY());
            int x1 = Math.min(w - 1, (int) (x0 + node.getLayoutBounds().getWidth()));
            int y1 = Math.min(h - 1, (int) (y0 + node.getLayoutBounds().getHeight()));
            int best = 0;
            double bestScore = -1;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int[] rgb = argbToRgb(r.getArgb(x, y));
                    double score = rgb[2] - rgb[0];
                    if (score > bestScore) {
                        bestScore = score;
                        best = x * 100000 + y;
                    }
                }
            }
            return new int[]{best / 100000, best % 100000};
        }

        /**
         * DWM evidence: FindWindow by title, then DwmSetWindowAttribute.
         * Codes: 0 = OK; non-zero = not supported on this build, printed, no crash.
         */
        private void dwm() {
            try {
                HWND hwnd = User32.INSTANCE.FindWindow(null, TITLE);
                if (hwnd == null) {
                    System.out.println("PROBE_DWM HWND=NOT_FOUND");
                    return;
                }
                IntByReference one = new IntByReference(1);
                int dark = DwmApiLib.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, one.getPointer(), 4);
                int mica = DwmApiLib.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_MICA_EFFECT, one.getPointer(), 4);
                IntByReference backdrop = new IntByReference(DWMSBT_MAINWINDOW);
                int back = DwmApiLib.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdrop.getPointer(), 4);
                System.out.println("PROBE_DWM hwnd=" + hwnd + " dark_mode(20)=" + dark
                        + " mica(102)=" + mica + " backdrop(38)=" + back + " codes:0=OK");
            } catch (Throwable t) {
                System.out.println("PROBE_DWM ERROR " + t);
            }
        }

        private String px(PixelReader r, int x, int y) {
            int[] rgb = argbToRgb(r.getArgb(x, y));
            return rgb[0] + "," + rgb[1] + "," + rgb[2];
        }

        private int[] argbToRgb(int argb) {
            return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
        }

        private int[] parseRgb(String s) {
            String[] parts = s.split(",");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        }
    }
}