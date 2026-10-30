package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import ai.cline.ide.jetbrains.util.ClineReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Prompts for free-form text and sends it (plus the current selection / file
 * reference) to the Cline TUI.
 */
public final class ClineAskWithPromptAction extends AnAction {

    public ClineAskWithPromptAction() {
        super("Ask Cline\u2026");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        String prompt = Messages.showInputDialog(project,
                "What should Cline do?", "Ask Cline", Messages.getQuestionIcon());
        if (prompt == null || prompt.isBlank()) return;
        String ref = ClineReference.from(project, e);
        String text = ref.isEmpty() ? prompt.trim() : ref + " " + prompt.trim();
        ServiceManager.getService(project, ClineService.class).focusOrCreateTerminalAndRun(text);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
