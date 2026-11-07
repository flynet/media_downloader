package com.example.hlsgrabber.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Paths of the jpackage app-image package (TZ R3: browsers are bundled inside the package).
 *
 * <p>App-image layout: {@code <root>\HLS Grabber.exe}, {@code <root>\app\}, {@code <root>\runtime\bin\java.exe}.
 * A plain dev run (fat jar via system java) has no package root and no bundled browsers.
 */
public final class AppPaths {

    private AppPaths() {
    }

    /** Package root of a jpackage app-image launch, null for a plain JVM run. */
    public static Path appImageRoot() {
        try {
            String cmd = ProcessHandle.current().info().command().orElse(null);
            if (cmd == null) {
                return null;
            }
            Path java = Paths.get(cmd).normalize();
            // <root>\runtime\bin\java.exe
            Path bin = java.getParent();
            if (bin == null) {
                return null;
            }
            Path runtime = bin.getParent();
            if (runtime == null) {
                return null;
            }
            Path root = runtime.getParent();
            if (root == null) {
                return null;
            }
            Path app = root.resolve("app");
            if (!Files.isDirectory(app)) {
                return null;
            }
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    /** Bundled browsers dir ({@code <root>\app\browsers}) if present, null otherwise. */
    public static Path bundledBrowsersDir() {
        Path root = appImageRoot();
        if (root == null) {
            return null;
        }
        Path b = root.resolve("app").resolve("browsers");
        return Files.isDirectory(b) ? b : null;
    }
}
