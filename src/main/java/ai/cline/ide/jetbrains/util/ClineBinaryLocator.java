package ai.cline.ide.jetbrains.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the Cline CLI executable ({@code cline} / {@code cline.cmd} on Windows).
 *
 * <p>Resolution order:
 * <ol>
 *   <li>the explicit path configured in Settings &gt; Tools &gt; Cline,</li>
 *   <li>the homebrew / npm-global locations used by the documented install
 *       ({@code npm install -g cline}),</li>
 *   <li>whatever {@code cline} is on {@code PATH} (the terminal shell resolves
 *       it itself, which also covers nvm / volta / bun installs).</li>
 * </ol>
 */
public final class ClineBinaryLocator {

    /** Bare command used when nothing else is found (resolved by the shell PATH). */
    public static final String FALLBACK_COMMAND = "cline";

    private ClineBinaryLocator() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /**
     * Resolve the command to launch in the terminal.
     *
     * @param configuredPath value of the "Cline CLI path" setting (may be blank)
     */
    public static String resolve(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            String trimmed = configuredPath.trim();
            if (Files.exists(Paths.get(trimmed))) return trimmed;
        }
        for (Path candidate : candidates()) {
            if (Files.exists(candidate) || Files.exists(withWindowsFallback(candidate))) return candidate.toString();
        }
        return FALLBACK_COMMAND;
    }

    /**
     * The npm bin directory that holds the {@code cline} launchers. Reporting it
     * lets the terminal friendly-remind the user which PATH entry is needed when
     * {@code cline} cannot be found.
     */
    public static Path npmBinDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Paths.get(appData, "npm")
                : Paths.get(System.getProperty("user.home", ""), "AppData", "Roaming", "npm");
        return base;
    }

    private static Path[] candidates() {
        String home = System.getProperty("user.home", "");
        if (isWindows()) {
            Path npm = npmBinDir().resolve("cline.cmd");
            return new Path[]{
                    npm,
                    Paths.get(home, "AppData", "Roaming", "npm", "cline.cmd"),
                    Paths.get(System.getenv("LOCALAPPDATA") == null ? home : System.getenv("LOCALAPPDATA"),
                            "Programs", "cline", "cline.cmd"),
                    Paths.get(home, ".bun", "bin", "cline.exe"),
            };
        }
        return new Path[]{
                Paths.get("/opt/homebrew/bin/cline"),
                Paths.get("/usr/local/bin/cline"),
                Paths.get("/usr/bin/cline"),
                Paths.get(home, ".local", "bin", "cline"),
                Paths.get(home, ".bun", "bin", "cline"),
        };
    }

    private static Path withWindowsFallback(Path p) {
        String s = p.toString();
        if (isWindows() && !s.endsWith(".cmd")) return Paths.get(s + ".cmd");
        return p;
    }

    /** True when the resolved command is an absolute file we can feed to the shell. */
    public static boolean isAbsolute(String command) {
        try {
            return Paths.get(command).isAbsolute();
        } catch (Exception e) {
            return false;
        }
    }

    /** Quote an executable path for cmd.exe / bash if it contains spaces. */
    public static String quote(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    /** Ensure the path exists, so the settings page can validate user input. */
    public static boolean isValidPath(String path) throws IOException {
        return path != null && !path.isBlank() && Files.exists(Paths.get(path.trim()));
    }
}
