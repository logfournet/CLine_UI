package ai.cline.ide.jetbrains.service;

import ai.cline.ide.jetbrains.ClinePlugin;
import ai.cline.ide.jetbrains.diff.ClineDiffViewer;
import ai.cline.ide.jetbrains.session.ClineChangeTracker;
import ai.cline.ide.jetbrains.session.ClineSession;
import ai.cline.ide.jetbrains.session.ClineSessionStore;
import ai.cline.ide.jetbrains.session.FileChange;
import ai.cline.ide.jetbrains.terminal.ClineTerminalLinkFilter;
import ai.cline.ide.jetbrains.ui.ClineSettingsConfigurable;
import ai.cline.ide.jetbrains.util.ClineBinaryLocator;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalPanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jediterm.terminal.TerminalStarter;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central project service.
 *
 * <p>Hosts the Cline CLI TUI in the docked <b>Cline</b> tool window, follows the
 * matching session in Cline's own session store
 * ({@code ~/.cline/data/sessions}) to know when a turn starts and ends, and
 * surfaces the resulting workspace diff through {@link ClineChangeTracker} and
 * {@link ClineDiffViewer}.
 *
 * <p>Because the CLI has no HTTP surface, everything the reference OpenCode
 * plugin took from its background service (<code>service.json</code>, SSE,
 * <code>/api/session/.../diff</code>) is derived here from files Cline writes
 * itself: the session JSON for status / model / mode, and a workspace snapshot
 * pair for the diff.
 */
@Service(Service.Level.PROJECT)
public final class ClineService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ClineService.class);

    /** How long the TUI needs before it accepts typed input after launch. */
    private static final long BOOT_DELAY_MS = 3500;
    /** Poll period for the Cline session store. */
    private static final long POLL_SECONDS = 2;
    /** Consecutive idle polls required before a turn is considered finished. */
    private static final int IDLE_STREAK_TO_FINISH = 2;
    /** The transcript must be quiet this long, so a streaming answer is not cut off. */
    private static final long QUIET_MS_TO_FINISH = 5000;
    /**
     * Safety valve: if the transcript shows a tool call that never completes
     * (the user aborted with Ctrl+C, or the process died) the turn is closed
     * after this long without any new message.
     */
    private static final long STUCK_BUSY_MS = 180_000;
    /** After "Stop" the turn is force-closed if the transcript does not settle. */
    private static final long INTERRUPT_GRACE_MS = 10_000;
    /**
     * IDE advanced setting that caps terminal scrollback
     * ({@code terminal.buffer.max.lines.count}, JetBrains default 5000 lines).
     */
    private static final String TERMINAL_MAX_LINES_KEY = "terminal.buffer.max.lines.count";

    private final Project project;
    private final ScheduledExecutorService poller;

    private volatile ShellTerminalWidget terminalWidget;
    private volatile JPanel terminalPanel;
    private final AtomicBoolean terminalCommandStarted = new AtomicBoolean(false);
    private volatile long terminalStartedAt;
    private volatile String cachedBinary;

    private volatile ClineSession session;
    private volatile boolean busy;
    private volatile int idleStreak;
    private volatile long lastMessagesMtime;
    private volatile long lastMessagesChangeAt;
    /** When the user asked to stop the turn (0 = no pending stop). */
    private volatile long interruptRequestedAt;

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public ClineService(Project project) {
        this.project = project;
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cline-service");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------
    // Docked panel (terminal hosting)
    // ------------------------------------------------------------------

    /**
     * Show the Cline tool window and make sure the TUI is running inside it.
     * The TUI lives in its own dockable panel (right side) instead of an editor
     * tab, so the code editor stays visible next to the agent.
     */
    public void focusOrCreateTerminal() {
        if (project.isDisposed()) return;
        startPolling();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                ToolWindow tw = getToolWindow();
                if (tw != null) tw.activate(null, false);
            } catch (Exception ignored) {
            }
        });
        ensureTerminalOnEdt();
    }

    /** True when the Cline terminal widget has been created (panel exists). */
    public boolean hasTerminalUI() {
        return terminalWidget != null;
    }

    /** Return (creating if needed) the panel hosting the Cline TUI. */
    public synchronized JPanel ensureTerminal() {
        return ensureTerminal(getToolWindow());
    }

    /**
     * Return (creating if needed) the JPanel hosting the TUI and make sure it is
     * the content of the given tool window.
     *
     * <p>{@code TerminalView.createLocalShellWidget} appends the terminal to the
     * global Terminal tool window, so the terminal's {@link Content} is moved
     * into the Cline tool window instead of re-parenting the widget (which would
     * let the Terminal window steal it back and leave the Cline panel blank).
     */
    public synchronized JPanel ensureTerminal(ToolWindow toolWindow) {
        ToolWindow tw = toolWindow != null ? toolWindow : getToolWindow();
        if (terminalWidget != null && terminalPanel != null) {
            attachPanel(tw, terminalPanel);
            return terminalPanel;
        }
        try {
            String wd = project.getBasePath();
            if (wd == null) {
                notify("Cline", "Open a project before starting the Cline panel.",
                        NotificationType.WARNING);
                return null;
            }
            // The scrollback cap is read when the terminal buffer is created,
            // so it must be applied before the widget exists.
            applyTerminalScrollback();
            // requestFocus=false: do NOT activate the global Terminal tool window.
            ShellTerminalWidget widget = TerminalView.getInstance(project)
                    .createLocalShellWidget(wd, ClinePlugin.PANEL_TITLE, false, true);
            ClineTerminalLinkFilter.install(project, widget);
            installEscIntercept(widget);

            JPanel panel = null;
            Content moved = moveTerminalContentInto(tw, widget);
            if (moved != null) {
                java.awt.Component comp = moved.getComponent();
                if (comp instanceof JPanel) {
                    panel = (JPanel) comp;
                } else if (comp != null) {
                    panel = new JPanel(new BorderLayout());
                    panel.add(comp, BorderLayout.CENTER);
                }
            }
            if (panel == null) {
                // Older platform: createLocalShellWidget returns a detached widget.
                panel = new JPanel(new BorderLayout());
                panel.add(widget.getComponent(), BorderLayout.CENTER);
                attachPanel(tw, panel);
            }
            installFileDrop(panel, widget);
            terminalWidget = widget;
            terminalPanel = panel;
            // Defer the launch until the widget is attached to a visible component.
            ApplicationManager.getApplication().invokeLater(this::startTerminalCommandIfNeeded);
            // Changes made before the panel was opened are diffed against this baseline.
            ServiceManager.getService(project, ClineChangeTracker.class).refreshBaseline();
            LOG.info("Cline terminal panel created");
        } catch (Throwable t) {
            LOG.warn("Cline terminal unavailable", t);
            notify("Cline", "Could not open the terminal panel: " + t.getMessage(),
                    NotificationType.ERROR);
            return null;
        }
        return terminalPanel;
    }

    /**
     * Move the {@link Content} that hosts {@code widget} from the global Terminal
     * tool window into {@code target}. Returns the moved content, or {@code null}
     * when the platform created a detached widget (older API).
     */
    private Content moveTerminalContentInto(ToolWindow target, ShellTerminalWidget widget) {
        if (target == null) return null;
        try {
            ToolWindow terminalTw = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
            if (terminalTw == null) return null;
            java.awt.Component widgetComponent = widget.getComponent();
            if (widgetComponent == null) return null;
            ContentManager terminalCm = terminalTw.getContentManager();
            for (Content c : terminalCm.getContents()) {
                java.awt.Component host = c.getComponent();
                if (host != null && javax.swing.SwingUtilities.isDescendingFrom(widgetComponent, host)) {
                    ContentManager targetCm = target.getContentManager();
                    for (Content old : targetCm.getContents()) targetCm.removeContent(old, true);
                    // Detach without disposing the widget, then attach it to our tool window.
                    terminalCm.removeContent(c, false);
                    targetCm.addContent(c);
                    targetCm.setSelectedContent(c, false);
                    LOG.info("Moved the Cline terminal into the Cline tool window");
                    return c;
                }
            }
        } catch (Throwable t) {
            LOG.warn("Could not relocate the terminal content", t);
        }
        return null;
    }

    /** Make sure the given panel is the content of the tool window (idempotent). */
    private void attachPanel(ToolWindow tw, JPanel panel) {
        try {
            if (tw == null) tw = getToolWindow();
            if (tw == null) return;
            ContentManager cm = tw.getContentManager();
            for (Content c : cm.getContents()) {
                if (c.getComponent() == panel) return; // already attached
            }
            for (Content c : cm.getContents()) cm.removeContent(c, true);
            cm.addContent(ContentFactory.getInstance().createContent(panel, "", false));
        } catch (Exception e) {
            LOG.warn("Failed to attach the Cline panel", e);
        }
    }

    private void ensureTerminalOnEdt() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ensureTerminal();
        } else {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) ensureTerminal();
            });
        }
    }

    /**
     * Accept files dropped onto the Cline panel and paste them as {@code @path}
     * references into the TUI input. The handler is installed on both the
     * wrapping panel and the terminal component so a drop anywhere works.
     */
    private void installFileDrop(JPanel panel, ShellTerminalWidget widget) {
        javax.swing.TransferHandler handler = new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop()
                        && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    java.util.List<java.io.File> files = (java.util.List<java.io.File>)
                            support.getTransferable()
                                    .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) return false;
                    String base = project.getBasePath();
                    StringBuilder sb = new StringBuilder();
                    for (java.io.File f : files) {
                        String path = f.getAbsolutePath().replace('\\', '/');
                        String rel = path;
                        if (base != null) {
                            String bp = base.replace('\\', '/');
                            if (path.startsWith(bp + "/")) rel = path.substring(bp.length() + 1);
                        }
                        sb.append('@').append(rel.contains(" ") ? "\"" + rel + "\"" : rel).append(' ');
                    }
                    if (sb.length() > 0) {
                        String text = sb.toString().trim() + " ";
                        ApplicationManager.getApplication().invokeLater(
                                () -> focusOrCreateTerminalAndPaste(text));
                    }
                    return true;
                } catch (Exception ex) {
                    LOG.debug("file drop import failed", ex);
                    return false;
                }
            }
        };
        panel.setTransferHandler(handler);
        try {
            widget.getComponent().setTransferHandler(handler);
        } catch (Throwable ignored) {
        }
    }

    /** Copy the whole terminal buffer to the clipboard as a Markdown code block. */
    public void copyOutputAsMarkdown() {
        ShellTerminalWidget w = terminalWidget;
        if (w == null) return;
        try {
            String text = w.getText();
            if (text == null || text.isBlank()) return;
            String md = "```\n" + text.stripTrailing() + "\n```";
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                    .setContents(new java.awt.datatransfer.StringSelection(md));
        } catch (Throwable t) {
            LOG.debug("copy output failed", t);
        }
    }

    /**
     * Stop IntelliJ from stealing ESC while the panel is focused.
     *
     * <p>The terminal installs an internal escape listener that consumes ESC and
     * moves the focus to the editor. Cline keeps the focus in its prompt, so we
     * consume the event first (which makes that listener ignore it) and forward
     * the raw ESC byte to the TUI — it closes its own popups / cancels input.
     * Aborting a running turn is Ctrl+C, which the terminal already forwards.
     */
    private void installEscIntercept(ShellTerminalWidget widget) {
        try {
            JBTerminalPanel panel = widget.getTerminalPanel();
            panel.addPreKeyEventHandler(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ESCAPE) return;
                e.consume();
                try {
                    TerminalStarter starter = widget.getTerminalStarter();
                    if (starter != null) starter.sendBytes(new byte[]{0x1b}, false);
                } catch (Throwable t) {
                    LOG.debug("could not forward ESC to the Cline TUI", t);
                }
            });
        } catch (Throwable t) {
            LOG.warn("Could not install the ESC intercept on the Cline terminal", t);
        }
    }

    /** Launch {@code cline} in the widget exactly once. */
    private void startTerminalCommandIfNeeded() {
        ShellTerminalWidget widget = terminalWidget;
        if (widget == null) return;
        if (!terminalCommandStarted.compareAndSet(false, true)) return;
        try {
            String command = buildCommand();
            LOG.info("Launching Cline: " + command);
            widget.executeCommand(command);
            terminalStartedAt = System.currentTimeMillis();
            startPolling();
        } catch (Throwable t) {
            terminalCommandStarted.set(false);
            LOG.warn("Failed to start cline in the terminal", t);
            notify("Cline", "Could not launch the Cline CLI. Check the path in "
                    + "Settings > Tools > Cline.", NotificationType.ERROR);
        }
    }

    /**
     * Command line used to launch the TUI.
     *
     * <p>Only flags the user actually configured are added, so the CLI keeps its
     * own defaults (model / provider / approval chosen inside the TUI):
     * <ul>
     *   <li>{@code --plan} when the panel should start in Plan mode,</li>
     *   <li>{@code --auto-approve true|false} when it is pinned,</li>
     *   <li>{@code -m} / {@code -P} for a model / provider override,</li>
     *   <li>plus the free-form "extra arguments" setting.</li>
     * </ul>
     */
    private String buildCommand() {
        StringBuilder sb = new StringBuilder(ClineBinaryLocator.quote(getClineBinary()));

        if ("plan".equalsIgnoreCase(readSetting(ClineSettingsConfigurable.START_MODE_KEY, "act"))) {
            sb.append(" --plan");
        }
        String autoApprove = readSetting(ClineSettingsConfigurable.AUTO_APPROVE_KEY, "");
        if (!autoApprove.isBlank()) sb.append(" --auto-approve ").append(autoApprove.trim());

        String model = readSetting(ClineSettingsConfigurable.MODEL_KEY, "");
        if (!model.isBlank()) sb.append(" -m ").append(quoteArg(model.trim()));

        String provider = readSetting(ClineSettingsConfigurable.PROVIDER_KEY, "");
        if (!provider.isBlank()) sb.append(" -P ").append(quoteArg(provider.trim()));

        String extra = readSetting(ClineSettingsConfigurable.EXTRA_ARGS_KEY, "");
        if (!extra.isBlank()) sb.append(' ').append(extra.trim());

        return sb.toString();
    }

    private String quoteArg(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    /** Resolve the Cline CLI binary (cached until the setting changes). */
    private String getClineBinary() {
        String cached = cachedBinary;
        if (cached != null) return cached;
        String resolved = ClineBinaryLocator.resolve(
                readSetting(ClineSettingsConfigurable.BINARY_PATH_KEY, ""));
        cachedBinary = resolved;
        return resolved;
    }

    // ------------------------------------------------------------------
    // Settings helpers
    // ------------------------------------------------------------------

    private String readSetting(String key, String def) {
        try {
            String v = PropertiesComponent.getInstance(project).getValue(key, def);
            return v == null || v.isBlank() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private int readIntSetting(String key, int def) {
        try {
            return PropertiesComponent.getInstance(project).getInt(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Apply the configured terminal scrollback size to the IDE advanced setting
     * {@code terminal.buffer.max.lines.count} (JetBrains default: 5000 lines).
     * {@code 0} means "no line limit" and maps to the largest accepted cap; the
     * buffer only grows with lines that are actually produced, so nothing is
     * reserved up front. The value is read when the terminal buffer is created.
     */
    private void applyTerminalScrollback() {
        try {
            int lines = readIntSetting(ClineSettingsConfigurable.TERMINAL_BUFFER_KEY,
                    ClineSettingsConfigurable.DEFAULT_TERMINAL_BUFFER_LINES);
            if (lines <= 0) lines = Integer.MAX_VALUE;
            AdvancedSettings.getInstance().setInt(TERMINAL_MAX_LINES_KEY, lines);
        } catch (Throwable t) {
            LOG.debug("could not apply the terminal scrollback setting", t);
        }
    }

    /** Forget the cached binary path (called after the setting changes). */
    public void resetCachedBinary() {
        cachedBinary = null;
    }

    /** Restart the TUI so launch-time settings (mode / model / scrollback) apply. */
    public void restartTerminal() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ShellTerminalWidget old = terminalWidget;
            terminalWidget = null;
            terminalPanel = null;
            terminalCommandStarted.set(false);
            terminalStartedAt = 0;
            if (old != null) {
                try {
                    old.dispose();
                } catch (Exception ignored) {
                }
            }
            ensureTerminal();
        });
    }

    // ------------------------------------------------------------------
    // Talking to the TUI
    // ------------------------------------------------------------------

    /** Focus the TUI and paste a reference (e.g. "@path#L1-5 ") as context. */
    public void focusOrCreateTerminalAndPaste(String text) {
        focusOrCreateTerminalAndType(text, false);
    }

    /**
     * Focus the TUI, type the given text and press Enter so the request starts
     * immediately (used by the code actions: Explain / Fix / Refactor …).
     */
    public void focusOrCreateTerminalAndRun(String text) {
        focusOrCreateTerminalAndType(text, true);
    }

    private void focusOrCreateTerminalAndType(String text, boolean submit) {
        if (text == null || text.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        // A TUI that was just launched needs a moment before it accepts input.
        long sinceStart = System.currentTimeMillis() - terminalStartedAt;
        long bootWait = terminalStartedAt == 0 ? 0 : Math.max(0, BOOT_DELAY_MS - sinceStart);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    try {
                        ToolWindow tw = getToolWindow();
                        if (tw != null) tw.activate(null, false);
                    } catch (Exception ignored) {
                    }
                    if (bootWait > 0) {
                        AppExecutorUtil.getAppScheduledExecutorService().schedule(
                                () -> ApplicationManager.getApplication().invokeLater(
                                        () -> typeIntoTerminal(widget, text, submit)),
                                bootWait, TimeUnit.MILLISECONDS);
                    } else {
                        typeIntoTerminal(widget, text, submit);
                    }
                } catch (Exception e) {
                    LOG.debug("typing into the Cline terminal failed", e);
                }
            });
        });
    }

    /** Send the text to the TUI and, when requested, press Enter shortly after. */
    private void typeIntoTerminal(ShellTerminalWidget w, String text, boolean submit) {
        if (project.isDisposed()) return;
        try {
            if (submit) {
                // Snapshot before the agent can touch anything.
                ServiceManager.getService(project, ClineChangeTracker.class).beginTurn();
                busy = true;
                idleStreak = 0;
                notifyChanged();
            }
            w.getTerminalStarter().sendString(text, false);
            if (submit) {
                // Let the TUI render the text (and close its @mention popup)
                // before Enter, so Enter submits instead of picking a completion.
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        () -> ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                if (!project.isDisposed()) {
                                    w.getTerminalStarter().sendBytes(new byte[]{'\r'}, false);
                                }
                            } catch (Throwable ignored) {
                            }
                        }), 450, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            LOG.debug("type into terminal failed", t);
        }
    }

    /** Type a line into the TUI and press Enter (slash commands). */
    public void sendCommandToTerminal(String command) {
        if (command == null || command.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            try {
                widget.getTerminalStarter().sendString(command + "\r", false);
            } catch (Throwable t) {
                LOG.debug("sendCommandToTerminal failed", t);
            }
        });
    }

    /** Send raw bytes to the TUI (Ctrl+C, Tab, Shift+Tab …). */
    private void sendBytes(byte[] bytes) {
        if (project.isDisposed()) return;
        focusOrCreateTerminal();
        ApplicationManager.getApplication().invokeLater(() -> {
            ShellTerminalWidget w = terminalWidget;
            if (w == null || project.isDisposed()) return;
            try {
                w.getTerminalStarter().sendBytes(bytes, false);
            } catch (Throwable t) {
                LOG.debug("writing to the Cline terminal failed", t);
            }
        });
    }

    /** Ctrl+C in the TUI aborts the running turn. */
    public void interrupt() {
        interruptRequestedAt = System.currentTimeMillis();
        sendBytes(new byte[]{0x03});
    }

    /** Tab switches the TUI between Plan and Act mode. */
    public void togglePlanAct() {
        sendBytes(new byte[]{0x09});
    }

    /** Shift+Tab toggles "auto-approve all" in the TUI (xterm CSI Z). */
    public void toggleAutoApprove() {
        sendBytes(new byte[]{0x1b, '[', 'Z'});
    }

    /**
     * Start a new task: {@code /clear} clears the conversation in the TUI and the
     * tracker takes a fresh baseline, so the next review only shows the changes
     * of the new task.
     */
    public void startNewTask() {
        busy = false;
        idleStreak = 0;
        ServiceManager.getService(project, ClineChangeTracker.class).refreshBaseline();
        notifyChanged();
        sendCommandToTerminal("/clear");
    }

    // ------------------------------------------------------------------
    // Session tracking
    // ------------------------------------------------------------------

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) return;
        if (disposed.get()) return;
        poller.scheduleWithFixedDelay(this::pollOnBackground, 1, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void pollOnBackground() {
        if (disposed.get() || project.isDisposed()) return;
        try {
            ClineSession found = ClineSessionStore.find(project);
            boolean changed = false;
            ClineSession previous = session;
            if (found == null) {
                if (previous != null) {
                    session = null;
                    busy = false;
                    notifyChanged();
                }
                return;
            }
            session = found;
            if (previous == null || !previous.id.equals(found.id)) changed = true;

            ClineSessionStore.Probe probe = ClineSessionStore.probe(found);
            long now = System.currentTimeMillis();
            if (probe.messagesMtime != lastMessagesMtime) {
                lastMessagesMtime = probe.messagesMtime;
                lastMessagesChangeAt = now;
                idleStreak = 0;
            }

            ClineChangeTracker tracker = ServiceManager.getService(project, ClineChangeTracker.class);
            if (probe.state == ClineSessionStore.TurnState.RUNNING) {
                idleStreak = 0;
                if (!busy) {
                    busy = true;
                    tracker.beginTurn();
                    changed = true;
                } else if (isTurnStuck(now)) {
                    // Ctrl+C / crashed tool: the transcript still advertises a
                    // tool call that will never finish.
                    LOG.info("Cline turn looks stuck - closing it");
                    busy = false;
                    interruptRequestedAt = 0;
                    changed = true;
                    finishTurn(tracker);
                }
            } else if (busy) {
                idleStreak++;
                boolean quiet = lastMessagesChangeAt > 0
                        && now - lastMessagesChangeAt >= QUIET_MS_TO_FINISH;
                boolean interrupted = interruptRequestedAt > 0
                        && now - interruptRequestedAt >= INTERRUPT_GRACE_MS;
                if ((idleStreak >= IDLE_STREAK_TO_FINISH && quiet) || interrupted) {
                    busy = false;
                    interruptRequestedAt = 0;
                    changed = true;
                    finishTurn(tracker);
                }
            }
            if (changed) notifyChanged();
        } catch (Exception e) {
            LOG.debug("Cline session poll failed", e);
        }
    }

    /** True when a "running" transcript has not moved for a long time. */
    private boolean isTurnStuck(long now) {
        if (interruptRequestedAt > 0 && now - interruptRequestedAt >= INTERRUPT_GRACE_MS) return true;
        return lastMessagesChangeAt > 0 && now - lastMessagesChangeAt >= STUCK_BUSY_MS;
    }

    /** Called on the poller thread once the transcript shows the turn is over. */
    private void finishTurn(ClineChangeTracker tracker) {
        List<FileChange> changes;
        try {
            changes = tracker.endTurn();
        } catch (Exception e) {
            LOG.warn("could not compute the Cline turn diff", e);
            return;
        }
        if (changes.isEmpty()) {
            LOG.info("Cline turn finished without file changes");
            return;
        }
        notifyTurnComplete(changes);
    }

    private void notifyTurnComplete(List<FileChange> changes) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                int added = 0;
                int modified = 0;
                int deleted = 0;
                for (FileChange c : changes) {
                    switch (c.status) {
                        case ADDED -> added++;
                        case DELETED -> deleted++;
                        default -> modified++;
                    }
                }
                String summary = changes.size() + " file(s) changed"
                        + (modified > 0 ? " · " + modified + " modified" : "")
                        + (added > 0 ? " · " + added + " added" : "")
                        + (deleted > 0 ? " · " + deleted + " deleted" : "");
                Notification n = NotificationGroupManager.getInstance()
                        .getNotificationGroup(ClinePlugin.NOTIFICATION_GROUP)
                        .createNotification("Cline finished", summary, NotificationType.INFORMATION);
                n.addAction(NotificationAction.createSimpleExpiring(
                        "Review Changes", () -> new ClineDiffViewer(project).show(changes)));
                n.notify(project);
            } catch (Exception e) {
                LOG.debug("completion notification failed", e);
            }
        });
    }

    /** Open the diff of the last turn (or of everything since the baseline). */
    public void reviewChanges() {
        ClineChangeTracker tracker = ServiceManager.getService(project, ClineChangeTracker.class);
        List<FileChange> changes = tracker.getLastChanges();
        if (changes.isEmpty() && !tracker.isTurnActive()) {
            changes = tracker.diffSinceBaseline();
        }
        if (changes.isEmpty()) {
            notify("Cline", "No changes to review yet.", NotificationType.INFORMATION);
            return;
        }
        new ClineDiffViewer(project).show(changes);
    }

    public List<FileChange> getLastChanges() {
        return ServiceManager.getService(project, ClineChangeTracker.class).getLastChanges();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public boolean isBusy() {
        return busy;
    }

    /** True when a Cline session for this project was found on disk. */
    public boolean hasSession() {
        return session != null;
    }

    public ClineSession getSession() {
        return session;
    }

    public String getStatusText() {
        ClineSession s = session;
        if (s == null) {
            return "Cline: no session yet — open the panel to start the TUI";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(busy ? "Cline: working" : "Cline: idle");
        sb.append(" · ").append(s.id);
        if (!s.mode.isBlank()) sb.append(" · ").append(s.mode).append(" mode");
        if (!s.model.isBlank()) sb.append(" · ").append(s.model);
        if (s.cwd != null && !s.cwd.isBlank()) sb.append("\n").append(s.cwd);
        return sb.toString();
    }

    /** Called when the panel was disposed (tool window closed for good / project close). */
    public void onTerminalDisposed() {
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
        terminalStartedAt = 0;
        LOG.info("Cline terminal disposed");
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void notifyChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception e) {
                LOG.debug("Cline listener failed", e);
            }
        }
    }

    private void notify(String title, String content, NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(ClinePlugin.NOTIFICATION_GROUP)
                    .createNotification(title, content, type)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }

    private ToolWindow getToolWindow() {
        try {
            return ToolWindowManager.getInstance(project).getToolWindow(ClinePlugin.TOOL_WINDOW_ID);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void dispose() {
        disposed.set(true);
        poller.shutdownNow();
        listeners.clear();
        terminalWidget = null;
        terminalPanel = null;
        session = null;
        busy = false;
    }
}
