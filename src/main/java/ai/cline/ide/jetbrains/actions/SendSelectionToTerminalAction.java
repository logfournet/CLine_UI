package ai.cline.ide.jetbrains.actions;

import ai.cline.ide.jetbrains.service.ClineService;
import ai.cline.ide.jetbrains.util.ClineReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Sends file references to the Cline panel:
 * <ul>
 *   <li>editor selection → {@code @path#Lstart-end} (a reference, not content),</li>
 *   <li>no selection → {@code @path} for the current file,</li>
 *   <li>Project View selection → {@code @path} for every file / directory.</li>
 * </ul>
 * If the Cline panel is not open yet it is opened first, then the reference is
 * pasted into the TUI input so a prompt can be typed right after it.
 */
public final class SendSelectionToTerminalAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        ClineService svc = ServiceManager.getService(project, ClineService.class);

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile[] virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        StringBuilder text = new StringBuilder();
        if (editor != null) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            String relative = file != null ? ClineReference.relative(project, file) : null;
            if (relative != null) {
                if (editor.getSelectionModel().hasSelection()) {
                    var document = editor.getDocument();
                    int startLine = document.getLineNumber(
                            editor.getSelectionModel().getSelectionStart()) + 1;
                    int endLine = document.getLineNumber(
                            editor.getSelectionModel().getSelectionEnd()) + 1;
                    text.append("@").append(ClineReference.fmt(relative))
                            .append("#L").append(startLine).append("-").append(endLine);
                } else {
                    text.append("@").append(ClineReference.fmt(relative));
                }
            }
        } else if (virtualFiles != null && virtualFiles.length > 0) {
            VirtualFile focused = e.getData(CommonDataKeys.VIRTUAL_FILE);
            for (VirtualFile f : normalize(virtualFiles, focused)) {
                text.append("@").append(ClineReference.fmt(ClineReference.relative(project, f)))
                        .append(' ');
            }
        }

        if (!text.isEmpty()) {
            svc.focusOrCreateTerminalAndPaste(text.toString().trim() + " ");
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile[] virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        boolean hasEditorContext = editor != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null;
        boolean hasFileSelection = virtualFiles != null && virtualFiles.length > 0;
        e.getPresentation().setEnabledAndVisible(hasEditorContext || hasFileSelection);
    }

    private List<VirtualFile> normalize(VirtualFile[] selected, VirtualFile focused) {
        LinkedHashMap<String, VirtualFile> all = new LinkedHashMap<>();
        for (VirtualFile f : selected) all.put(f.getPath(), f);
        if (focused != null && focused.isDirectory()) all.putIfAbsent(focused.getPath(), focused);
        List<VirtualFile> sorted = new ArrayList<>(all.values());
        sorted.sort((a, b) -> a.getPath().length() - b.getPath().length());
        List<VirtualFile> kept = new ArrayList<>();
        for (VirtualFile candidate : sorted) {
            String cp = normalizePath(candidate.getPath());
            boolean hasParent = kept.stream().anyMatch(parent -> {
                String pp = normalizePath(parent.getPath());
                return cp.equals(pp) || cp.startsWith(pp + "/");
            });
            if (!hasParent) kept.add(candidate);
        }
        return kept;
    }

    private String normalizePath(String p) {
        String v = p.replace('\\', '/');
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
