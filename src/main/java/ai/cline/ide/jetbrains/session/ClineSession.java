package ai.cline.ide.jetbrains.session;

import java.nio.file.Path;

/**
 * A Cline CLI session as recorded in {@code ~/.cline/data/sessions}.
 */
public final class ClineSession {
    public String id = "";
    /** running | idle | completed | unknown */
    public String status = "unknown";
    public String cwd = "";
    public String model = "";
    public String provider = "";
    /** First prompt of the session (used for the notification title). */
    public String prompt = "";
    /** Tab title chosen by Cline, when present. */
    public String title = "";
    /** "act" or "plan". */
    public String mode = "";
    /** True for TUI (interactive) sessions; false for headless runs. */
    public boolean interactive = true;
    public long pid;
    public long startedAt;
    public Path file;
    public Path messagesPath;

    public boolean isRunning() {
        return "running".equalsIgnoreCase(status);
    }

    public boolean isInteractiveIdle() {
        return interactive && !isRunning();
    }

    /** Short label for the status bar / notifications. */
    public String label() {
        String t = title != null && !title.isBlank() ? title : prompt;
        if (t == null || t.isBlank()) return id;
        String firstLine = t.split("\\R", 2)[0].trim();
        return firstLine.length() > 60 ? firstLine.substring(0, 57) + "…" : firstLine;
    }

    @Override
    public String toString() {
        return id + " (" + status + ", " + cwd + ")";
    }
}
