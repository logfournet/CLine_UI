package ai.cline.ide.jetbrains.diff;

import ai.cline.ide.jetbrains.ClinePlugin;
import ai.cline.ide.jetbrains.session.ClineChangeTracker;
import ai.cline.ide.jetbrains.session.FileChange;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Applies Accept / Reject decisions from the diff viewer to the filesystem.
 *
 * <p>Accept keeps what Cline wrote (and stages it when the project is a git
 * repository). Reject restores the pre-turn content captured by
 * {@link ClineChangeTracker}, or deletes the file when Cline created it.
 */
@Service(Service.Level.PROJECT)
public final class ClineDiffHandler {

    private static final Logger LOG = Logger.getInstance(ClineDiffHandler.class);

    private final Project project;

    public ClineDiffHandler(Project project) {
        this.project = project;
    }

    public void accept(FileChange change) {
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().runWriteAction(() -> {
                    ClineChangeTracker tracker = ServiceManager.getService(project, ClineChangeTracker.class);
                    boolean ok;
                    if (change.isDeleted()) {
                        ok = tracker.deleteFile(change.file);
                    } else {
                        ok = tracker.writeFile(change.file, change.after);
                        if (ok) gitAdd(change.file);
                    }
                    notifyResult("Accepted", change.file, ok);
                }));
    }

    public void reject(FileChange change) {
        if (ServiceManager.getService(project, ClineChangeTracker.class).userEdited(change.file)) {
            LOG.info("Rejecting a file the user edited during the turn: " + change.file);
        }
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().runWriteAction(() -> {
                    ClineChangeTracker tracker = ServiceManager.getService(project, ClineChangeTracker.class);
                    boolean ok = change.isNewFile()
                            ? tracker.deleteFile(change.file)
                            : tracker.writeFile(change.file, change.before);
                    notifyResult("Rejected", change.file, ok);
                }));
    }

    private void gitAdd(String path) {
        // Only touch git when the project actually is a git repository: running
        // `git add` anywhere else would fail (or worse, create a stray repo).
        if (!isGitProject()) return;
        try {
            String base = project.getBasePath();
            ProcessBuilder pb = new ProcessBuilder("git", "add", "--", path);
            if (base != null) pb.directory(new File(base));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) {
            LOG.debug("git add failed: " + path, e);
        }
    }

    /** True only when the project has a git repository (no VCS dependency needed). */
    private boolean isGitProject() {
        try {
            String base = project.getBasePath();
            if (base == null) return false;
            // `.git` is a directory in a normal clone and a file in a worktree/submodule.
            return new File(base, ".git").exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyResult(String action, String file, boolean ok) {
        try {
            NotificationType type = ok ? NotificationType.INFORMATION : NotificationType.ERROR;
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(ClinePlugin.NOTIFICATION_GROUP)
                    .createNotification(action, file + (ok ? "" : " failed"), type)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }
}
