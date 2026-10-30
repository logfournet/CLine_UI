package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Aborts the running Cline turn by sending Ctrl+C to the TUI (Cline's own
 * abort binding).
 */
public final class InterruptAction extends AnAction {

    public InterruptAction() {
        super("Stop");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, ClineService.class).interrupt();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        boolean busy = p != null && !p.isDisposed()
                && ServiceManager.getService(p, ClineService.class).isBusy();
        e.getPresentation().setEnabled(busy);
    }
}
