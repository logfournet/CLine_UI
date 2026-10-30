package ai.cline.ide.jetbrains.diff;

import ai.cline.ide.jetbrains.session.FileChange;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Opens IntelliJ's native multi-file diff viewer for the files a Cline turn
 * changed, with per-file <b>Accept</b> / <b>Reject</b> context actions.
 */
public final class ClineDiffViewer {

    private final Project project;

    public ClineDiffViewer(Project project) {
        this.project = project;
    }

    public void show(List<FileChange> changes) {
        if (changes == null || changes.isEmpty() || project.isDisposed()) return;

        List<DiffRequest> requests = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            FileChange change = changes.get(i);
            FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.file);
            String title = "[" + (i + 1) + "/" + changes.size() + "] " + change.file;
            SimpleDiffRequest request = new SimpleDiffRequest(
                    title,
                    DiffContentFactory.getInstance().create(project, change.before, fileType),
                    DiffContentFactory.getInstance().create(project, change.after, fileType),
                    "Before Cline",
                    "After Cline"
            );
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, Arrays.asList(
                    new AcceptAction(change),
                    new RejectAction(change)
            ));
            requests.add(request);
        }

        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(requests);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT);
            }
        });
    }

    private static final class AcceptAction extends AnAction {
        private final FileChange change;

        AcceptAction(FileChange change) {
            super("Accept");
            this.change = change;
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project p = e.getProject();
            if (p == null || p.isDisposed()) return;
            ServiceManager.getService(p, ClineDiffHandler.class).accept(change);
        }
    }

    private static final class RejectAction extends AnAction {
        private final FileChange change;

        RejectAction(FileChange change) {
            super("Reject (restore)");
            this.change = change;
        }

        @Override
        public void actionPerformed(AnActionEvent e) {
            Project p = e.getProject();
            if (p == null || p.isDisposed()) return;
            ServiceManager.getService(p, ClineDiffHandler.class).reject(change);
        }
    }
}
