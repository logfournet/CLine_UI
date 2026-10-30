package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import ai.cline.ide.jetbrains.util.ClineReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * A code action that pastes a canned prompt (plus the current selection / file
 * reference) into the Cline TUI and submits it, so the request starts without an
 * extra keypress.
 */
public class ClineAskAction extends AnAction {

    private final String prompt;

    public ClineAskAction(@NotNull String text, @NotNull String prompt) {
        super(text);
        this.prompt = prompt;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        String ref = ClineReference.from(project, e);
        // Reference first, space-terminated, so the TUI's @mention completion
        // popup is already closed by the time the prompt is submitted.
        String text = ref.isEmpty() ? prompt : ref + " " + prompt;
        ServiceManager.getService(project, ClineService.class).focusOrCreateTerminalAndRun(text);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
