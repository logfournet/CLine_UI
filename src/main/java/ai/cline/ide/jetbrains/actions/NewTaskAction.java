package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Clears the conversation in the Cline TUI ({@code /clear}) and starts a fresh
 * baseline, so the next review only shows the new task's changes.
 */
public final class NewTaskAction extends AnAction {

    public NewTaskAction() {
        super("New Task");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, ClineService.class).startNewTask();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
