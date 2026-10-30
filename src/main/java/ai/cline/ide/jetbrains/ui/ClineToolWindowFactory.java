package ai.cline.ide.jetbrains.ui;

import ai.cline.ide.jetbrains.actions.CopyOutputAction;
import ai.cline.ide.jetbrains.actions.InterruptAction;
import ai.cline.ide.jetbrains.actions.NewTaskAction;
import ai.cline.ide.jetbrains.actions.ReviewChangesAction;
import ai.cline.ide.jetbrains.actions.TogglePlanActAction;
import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Cline docked panel.
 *
 * <p>The Cline TUI is hosted in this tool window's content (a real terminal
 * widget from the Terminal plugin) instead of an editor tab, which gives the
 * familiar "file tree | code | agent" layout: the code editor stays visible
 * next to the Cline panel while the agent works.
 *
 * <p>The panel itself is created by {@link ClineService#ensureTerminal(ToolWindow)};
 * this factory only makes sure it exists when the tool window is first shown and
 * adds the quick actions to the title bar.
 */
public final class ClineToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ClineService svc = ServiceManager.getService(project, ClineService.class);
        svc.ensureTerminal(toolWindow);
        // Quick actions in the tool window title bar.
        toolWindow.setTitleActions(List.of(
                new NewTaskAction(),
                new InterruptAction(),
                new TogglePlanActAction(),
                new ReviewChangesAction(),
                new CopyOutputAction()
        ));
    }
}
