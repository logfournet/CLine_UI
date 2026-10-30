package ai.cline.ide.jetbrains.session;

import ai.cline.ide.jetbrains.ClinePlugin;
import com.intellij.history.LocalHistory;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records what a Cline turn changed.
 *
 * <p>Cline CLI has no HTTP API that reports a diff, so the plugin takes a cheap
 * snapshot of the workspace when a turn starts and compares it with the
 * workspace when the turn ends. The result feeds the native diff viewer
 * (Accept / Reject) and the completion notification.
 *
 * <p>Every turn is also recorded in the IDE's built-in Local History (no VCS
 * required): a <i>Cline turn start</i> label before the agent touches anything
 * and a <i>Cline turn finished</i> label afterwards, so a change can always be
 * rolled back with right-click → Local History → Show History.
 */
@Service(Service.Level.PROJECT)
public final class ClineChangeTracker implements Disposable {

    private static final Logger LOG = Logger.getInstance(ClineChangeTracker.class);

    /** Upper bound of files shown in one review (keeps the diff viewer usable). */
    private static final int MAX_DIFF_FILES = 200;

    private final Project project;
    private final AtomicBoolean turnActive = new AtomicBoolean(false);

    private volatile Future<Map<String, byte[]>> startSnapshot;
    private volatile Map<String, byte[]> baseline = Map.of();
    private volatile List<FileChange> lastChanges = List.of();

    /** Files the user edited by hand during the turn (warn before rejecting). */
    private final Set<String> userEdited = ConcurrentHashMap.newKeySet();

    public ClineChangeTracker(Project project) {
        this.project = project;
        registerUserEditListener();
    }

    private void registerUserEditListener() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
                    @Override
                    public void documentChanged(DocumentEvent event) {
                        if (!turnActive.get()) return;
                        VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                        if (file == null) return;
                        if (isUserCommand()) userEdited.add(relative(file.getPath()));
                    }
                }, this);
            } catch (Exception e) {
                LOG.debug("could not register the document listener", e);
            }
        });
    }

    private boolean isUserCommand() {
        try {
            String name = CommandProcessor.getInstance().getCurrentCommandName();
            return name != null && !name.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Turn lifecycle
    // ------------------------------------------------------------------

    /** Snapshot the workspace and mark the turn in Local History. */
    public void beginTurn() {
        if (!turnActive.compareAndSet(false, true)) return;
        userEdited.clear();
        putLabel(ClinePlugin.LABEL_TURN_START);
        final String base = project.getBasePath();
        startSnapshot = AppExecutorUtil.getAppExecutorService()
                .submit(() -> WorkspaceSnapshot.capture(base));
        LOG.info("Cline turn started - snapshotting the workspace");
    }

    /**
     * Compare the workspace with the turn-start snapshot, store the result and
     * mark the end of the turn in Local History.
     */
    public List<FileChange> endTurn() {
        if (!turnActive.compareAndSet(true, false)) return lastChanges;
        putLabel(ClinePlugin.LABEL_TURN_END);
        Map<String, byte[]> before = awaitStartSnapshot();
        Map<String, byte[]> after = WorkspaceSnapshot.capture(project.getBasePath());
        List<FileChange> changes = diff(before, after);
        baseline = after;
        lastChanges = changes;
        LOG.info("Cline turn finished - " + changes.size() + " changed file(s)");
        return changes;
    }

    /**
     * Snapshot the workspace right now and use it as the baseline for a later
     * "Review Changes" (called when the panel is opened, so changes made by a
     * Cline instance started outside the IDE can still be reviewed).
     */
    public void refreshBaseline() {
        if (turnActive.get()) return;
        final String base = project.getBasePath();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                baseline = WorkspaceSnapshot.capture(base);
            } catch (Exception e) {
                LOG.debug("baseline snapshot failed", e);
            }
        });
    }

    private Map<String, byte[]> awaitStartSnapshot() {
        Future<Map<String, byte[]>> f = startSnapshot;
        if (f == null) return Map.of();
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("turn-start snapshot did not finish; the diff may be incomplete", e);
            return Map.of();
        }
    }

    private static List<FileChange> diff(Map<String, byte[]> before, Map<String, byte[]> after) {
        List<FileChange> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            byte[] now = after.get(e.getKey());
            if (now == null) {
                out.add(new FileChange(e.getKey(), FileChange.Status.DELETED,
                        text(e.getValue()), ""));
            } else if (!Arrays.equals(now, e.getValue())) {
                out.add(new FileChange(e.getKey(), FileChange.Status.MODIFIED,
                        text(e.getValue()), text(now)));
            }
        }
        for (Map.Entry<String, byte[]> e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) {
                out.add(new FileChange(e.getKey(), FileChange.Status.ADDED, "", text(e.getValue())));
            }
        }
        out.sort((a, b) -> a.file.compareToIgnoreCase(b.file));
        return out.size() > MAX_DIFF_FILES ? new ArrayList<>(out.subList(0, MAX_DIFF_FILES)) : out;
    }

    private static String text(byte[] data) {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    private void putLabel(String label) {
        try {
            if (!project.isDisposed()) LocalHistory.getInstance().putSystemLabel(project, label);
        } catch (Exception e) {
            LOG.debug("local history label failed: " + label, e);
        }
    }

    // ------------------------------------------------------------------
    // Accessors + file operations used by Accept / Reject
    // ------------------------------------------------------------------

    public List<FileChange> getLastChanges() {
        return lastChanges;
    }

    /** Diff of everything changed since the baseline snapshot. */
    public List<FileChange> diffSinceBaseline() {
        Map<String, byte[]> before = baseline;
        Map<String, byte[]> now = WorkspaceSnapshot.capture(project.getBasePath());
        if (before.isEmpty()) {
            // No baseline yet (the panel was opened a moment ago): adopt the
            // current state instead of reporting the whole project as added.
            baseline = now;
            return List.of();
        }
        baseline = now;
        return diff(before, now);
    }

    public boolean isTurnActive() {
        return turnActive.get();
    }

    public boolean userEdited(String relativePath) {
        return userEdited.contains(relativePath);
    }

    /** Write content to a project file (used by Reject). */
    public boolean writeFile(String relativePath, String content) {
        try {
            File target = resolve(relativePath);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
            refresh(target);
            return true;
        } catch (Exception e) {
            LOG.warn("could not write " + relativePath, e);
            return false;
        }
    }

    /** Delete a project file (used by Reject of files Cline created). */
    public boolean deleteFile(String relativePath) {
        try {
            File target = resolve(relativePath);
            boolean ok = !target.exists() || target.delete();
            refresh(target);
            return ok;
        } catch (Exception e) {
            LOG.warn("could not delete " + relativePath, e);
            return false;
        }
    }

    private File resolve(String relativePath) {
        String base = project.getBasePath();
        return base == null
                ? new File(relativePath)
                : new File(base, relativePath.replace('/', File.separatorChar));
    }

    private String relative(String path) {
        String base = project.getBasePath();
        if (base == null) return path.replace('\\', '/');
        String normalizedBase = base.replace('\\', '/');
        if (!normalizedBase.endsWith("/")) normalizedBase += "/";
        String p = path.replace('\\', '/');
        return p.startsWith(normalizedBase) ? p.substring(normalizedBase.length()) : p;
    }

    private void refresh(File target) {
        File toRefresh = target;
        if (!toRefresh.exists() && toRefresh.getParentFile() != null) toRefresh = toRefresh.getParentFile();
        if (toRefresh.exists()) {
            LocalFileSystem.getInstance().refreshIoFiles(List.of(toRefresh), false, false, null);
        }
    }

    @Override
    public void dispose() {
        turnActive.set(false);
        baseline = Map.of();
        lastChanges = List.of();
        userEdited.clear();
    }
}
