package ai.cline.ide.jetbrains.session;

import java.util.Map;

/**
 * Content snapshot of the workspace, used to compute the diff of one Cline turn
 * without relying on a VCS.
 *
 * <p>Snapshots are cheap because only real source files are kept: build output,
 * VCS metadata, dependency directories and binary files are skipped, and the
 * total retained size is capped.
 */
final class WorkspaceSnapshot {

    private WorkspaceSnapshot() {}

    /** relative path (forward slashes) -> raw file content */
    static Map<String, byte[]> capture(String basePath) {
        return new SnapshotWalker(basePath).walk();
    }

    static boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) return true;
        }
        return false;
    }
}
