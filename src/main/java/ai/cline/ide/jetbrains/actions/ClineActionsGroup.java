package ai.cline.ide.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

/**
 * "Cline" submenu in the editor context menu: canned code actions (Explain /
 * Fix / Refactor / Add tests / Optimize / Document) plus a free-form prompt
 * entry. Each action sends a prompt + the current selection reference to the
 * Cline TUI.
 */
public final class ClineActionsGroup extends DefaultActionGroup {

    public ClineActionsGroup() {
        getTemplatePresentation().setText("Cline");
        getTemplatePresentation().setIcon(IconLoader.getIcon("/icons/cline.svg", ClineActionsGroup.class));
        setPopup(true);

        add(new ClineAskAction("Explain", "Explain"));
        add(new ClineAskAction("Fix", "Fix the problems in"));
        add(new ClineAskAction("Refactor", "Refactor"));
        add(new ClineAskAction("Add Tests", "Write tests for"));
        add(new ClineAskAction("Optimize", "Optimize"));
        add(new ClineAskAction("Document", "Add documentation for"));
        add(Separator.getInstance());
        add(new ClineAskWithPromptAction());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
