package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Quick launch: opens or focuses the Cline panel.
 * Default shortcut: Ctrl+Alt+C (Cmd+Alt+C on macOS).
 */
public final class QuickLaunchAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, ClineService.class).focusOrCreateTerminal();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
