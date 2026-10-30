package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the native diff viewer for the changes of the last Cline turn (or for
 * everything changed since the panel's baseline snapshot).
 */
public final class ReviewChangesAction extends AnAction {

    public ReviewChangesAction() {
        super("Review Changes");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, ClineService.class).reviewChanges();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
