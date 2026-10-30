package ai.cline.ide.jetbrains.terminal;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes file references printed by Cline clickable inside the Cline terminal.
 *
 * <p>Two shapes are recognised:
 * <ul>
 *   <li>{@code @path}, {@code @"path with spaces"}, {@code @path#Lx-y} — Cline's
 *       own file mentions, exactly what the "Add to Cline" actions paste;</li>
 *   <li>{@code path/to/File.java:42} (and {@code :42:7}) — the form Cline uses
 *       when it reports an edit or a problem.</li>
 * </ul>
 * Clicking opens the file and highlights / scrolls to the referenced lines.
 */
public final class ClineTerminalLinkFilter implements HyperlinkFilter {

    private static final Logger LOG = Logger.getInstance(ClineTerminalLinkFilter.class);

    /** {@code @path}, {@code @"path with spaces"}, {@code @path#Lx-y} */
    private static final Pattern MENTION =
            Pattern.compile("@(\"([^\"]+)\"|([^\\s#]+))(?:#L(\\d+)(?:-(\\d+))?)?");
    /** {@code relative/path.ext:line[:column]} — must contain a slash to stay unambiguous. */
    private static final Pattern PATH_LINE =
            Pattern.compile("(?<![\\w@./\\\\-])((?:[\\w.\\-]+[/\\\\])+[\\w.\\-]+\\.[A-Za-z0-9]{1,8}):(\\d+)(?::\\d+)?\\b");

    private final Project project;

    private ClineTerminalLinkFilter(Project project) {
        this.project = project;
    }

    @Override
    public LinkResult apply(String line) {
        List<LinkResultItem> items = new ArrayList<>();

        Matcher m = MENTION.matcher(line);
        while (m.find()) {
            String raw = m.group(2) != null ? m.group(2) : m.group(3);
            if (raw == null) continue;
            String filePath = raw.replace("\uE000", "");
            addItem(items, filePath, parseInt(m.group(4)), parseInt(m.group(5)), m.start(), m.end());
        }

        Matcher p = PATH_LINE.matcher(line);
        while (p.find()) {
            String filePath = p.group(1).replace("\uE000", "");
            if (filePath.startsWith("http") || filePath.startsWith("www.")) continue;
            int start = parseInt(p.group(2));
            addItem(items, filePath, start, start, p.start(), p.end());
        }

        return items.isEmpty() ? null : new LinkResult(items);
    }

    private void addItem(List<LinkResultItem> items, String filePath,
                         int startLine, int endLine, int start, int end) {
        if (filePath == null || filePath.isBlank()) return;
        if (endLine <= 0) endLine = startLine;
        final int from = startLine;
        final int to = endLine;
        LinkInfo info = new LinkInfo(() -> navigate(project, filePath, from, to));
        items.add(new LinkResultItem(start, end, info));
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void navigate(Project project, String filePath, int startLine, int endLine) {
        VirtualFile file = resolveFile(project, filePath);
        if (file == null) {
            LOG.info("Cline link target not found: " + filePath);
            return;
        }
        OpenFileDescriptor descriptor = startLine > 0
                ? new OpenFileDescriptor(project, file, Math.max(startLine, 1) - 1, 0)
                : new OpenFileDescriptor(project, file);

        ApplicationManager.getApplication().invokeLater(() -> {
            var editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
            if (editor != null && startLine > 0) {
                var document = editor.getDocument();
                int startIndex = Math.min(Math.max(startLine - 1, 0), document.getLineCount() - 1);
                int endIndex = Math.min(Math.max(endLine - 1, startIndex), document.getLineCount() - 1);
                int startOffset = document.getLineStartOffset(startIndex);
                int endOffset = document.getLineEndOffset(endIndex);
                editor.getSelectionModel().setSelection(startOffset, endOffset);
                editor.getCaretModel().moveToOffset(startOffset);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            }
        });
    }

    private VirtualFile resolveFile(Project project, String filePath) {
        Path resolved = resolvePath(project, filePath);
        if (resolved == null) return null;
        return LocalFileSystem.getInstance().findFileByPath(resolved.toString().replace('\\', '/'));
    }

    private Path resolvePath(Project project, String maybeRelative) {
        try {
            Path p = Paths.get(maybeRelative);
            if (p.isAbsolute()) return Files.exists(p) ? p.toAbsolutePath() : null;
            String base = project.getBasePath();
            if (base == null) return null;
            Path candidate = Paths.get(base).resolve(p).normalize();
            return Files.exists(candidate) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void install(Project project, ShellTerminalWidget widget) {
        widget.addHyperlinkFilter(new ClineTerminalLinkFilter(project));
    }
}
