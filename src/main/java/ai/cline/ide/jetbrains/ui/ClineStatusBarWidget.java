package ai.cline.ide.jetbrains.ui;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Status-bar widget showing the Cline session state.
 * Clicking it opens or focuses the Cline panel.
 */
public final class ClineStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    private final Project project;
    private final ClineService service;
    private volatile StatusBar statusBar;
    private final Runnable refresh = this::refreshWidget;
    private final javax.swing.Timer spinner;
    private int frame;

    private static final String[] FRAMES = {"\u280B", "\u2819", "\u2839", "\u2838", "\u283C",
            "\u2834", "\u2826", "\u2827", "\u2807", "\u280F"};

    public ClineStatusBarWidget(@NotNull Project project) {
        this.project = project;
        this.service = ServiceManager.getService(project, ClineService.class);
        // Animate the spinner while Cline is working on a turn.
        this.spinner = new javax.swing.Timer(120, e -> {
            if (service.isBusy()) {
                frame = (frame + 1) % FRAMES.length;
                refreshWidget();
            }
        });
    }

    @Override
    public @NotNull @NonNls String ID() {
        return "ClineStatusBar";
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        service.addListener(refresh);
        if (!spinner.isRunning()) spinner.start();
    }

    @Override
    public void dispose() {
        spinner.stop();
        service.removeListener(refresh);
        statusBar = null;
    }

    private void refreshWidget() {
        StatusBar sb = statusBar;
        if (sb == null || project.isDisposed()) return;
        try {
            sb.updateWidget(ID());
        } catch (Exception ignored) {
            // The status bar may already be gone during shutdown.
        }
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation(@NotNull PlatformType type) {
        return this;
    }

    // ---- TextPresentation ----

    @Override
    public @NotNull String getText() {
        if (service.isBusy()) return FRAMES[frame] + " Cline";
        if (!service.hasSession()) return "\u25CB Cline";
        return "\u25C9 Cline";
    }

    @Override
    public @NotNull String getTooltipText() {
        return service.getStatusText();
    }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> service.focusOrCreateTerminal();
    }
}
