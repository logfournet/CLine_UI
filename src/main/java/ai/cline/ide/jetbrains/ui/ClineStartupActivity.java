package ai.cline.ide.jetbrains.ui;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the Cline panel when a project is opened, controlled by the
 * "Open the panel when a project opens" setting.
 */
public final class ClineStartupActivity implements StartupActivity {

    @Override
    public void runActivity(@NotNull Project project) {
        if (project.isDisposed()) return;
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        if (!pc.getBoolean(ClineSettingsConfigurable.OPEN_ON_STARTUP_KEY, false)) return;
        ServiceManager.getService(project, ClineService.class).focusOrCreateTerminal();
    }
}
