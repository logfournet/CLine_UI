package ai.cline.ide.jetbrains.session;

/**
 * One file changed during a Cline turn: the content before the turn and the
 * content now. Produced by {@link ClineChangeTracker} from its workspace
 * snapshots (no VCS required).
 */
public final class FileChange {

    public enum Status { ADDED, MODIFIED, DELETED }

    /** Path relative to the project root, forward slashes. */
    public final String file;
    public final Status status;
    public final String before;
    public final String after;

    public FileChange(String file, Status status, String before, String after) {
        this.file = file;
        this.status = status;
        this.before = before == null ? "" : before;
        this.after = after == null ? "" : after;
    }

    public boolean isNewFile() {
        return status == Status.ADDED;
    }

    public boolean isDeleted() {
        return status == Status.DELETED;
    }

    public int addedLines() {
        return countLines(after);
    }

    public int removedLines() {
        return countLines(before);
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int lines = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return lines + 1;
    }

    @Override
    public String toString() {
        return status + " " + file;
    }
}
