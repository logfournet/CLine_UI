package ai.cline.ide.jetbrains.ui;

import ai.cline.ide.jetbrains.service.ClineService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Per-project settings page for the Cline plugin (Settings &gt; Tools &gt; Cline).
 *
 * <p>Values live in the project's {@link PropertiesComponent}, so two IDE windows
 * can drive different Cline setups (model, mode, extra flags).
 */
public final class ClineSettingsConfigurable implements Configurable {

    public static final String PREFIX = "cline.";
    public static final String BINARY_PATH_KEY = PREFIX + "binaryPath";
    /** {@code act} or {@code plan} — passed as {@code --plan} at launch. */
    public static final String START_MODE_KEY = PREFIX + "startMode";
    /** {@code ""} (CLI default), {@code true} or {@code false}. */
    public static final String AUTO_APPROVE_KEY = PREFIX + "autoApprove";
    public static final String MODEL_KEY = PREFIX + "model";
    public static final String PROVIDER_KEY = PREFIX + "provider";
    public static final String EXTRA_ARGS_KEY = PREFIX + "extraArgs";
    public static final String OPEN_ON_STARTUP_KEY = PREFIX + "openOnStartup";
    /**
     * Terminal scrollback size in lines, applied to the IDE advanced setting
     * {@code terminal.buffer.max.lines.count}. {@link #UNLIMITED_TERMINAL_BUFFER_LINES}
     * means "no line limit" and is the default, so long Cline output is never
     * trimmed (the buffer only grows with lines that are actually produced).
     */
    public static final String TERMINAL_BUFFER_KEY = PREFIX + "terminalBufferLines";
    public static final int UNLIMITED_TERMINAL_BUFFER_LINES = 0;
    public static final int DEFAULT_TERMINAL_BUFFER_LINES = UNLIMITED_TERMINAL_BUFFER_LINES;
    public static final int DEFAULT_FINITE_TERMINAL_BUFFER_LINES = 100000;
    public static final int MIN_TERMINAL_BUFFER_LINES = 1000;
    public static final int MAX_TERMINAL_BUFFER_LINES = 1000000;

    private final Project project;

    private JPanel root;
    private JBTextField binaryPathField;
    private JComboBox<String> startModeCombo;
    private JComboBox<String> autoApproveCombo;
    private JBTextField modelField;
    private JBTextField providerField;
    private JBTextField extraArgsField;
    private JBCheckBox openOnStartupCheck;
    private JSpinner terminalBufferSpinner;
    private JBCheckBox unlimitedBufferCheck;

    public ClineSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable JComponent createComponent() {
        root = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 0;
        c.gridx = 0;
        c.gridy = 0;

        // Cline CLI path
        root.add(new JLabel("Cline CLI:"), c);
        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect (cline on PATH)");
        c.gridx = 1;
        c.weightx = 1;
        root.add(binaryPathField, c);
        JButton browse = new JButton("...");
        c.gridx = 2;
        c.weightx = 0;
        root.add(browse, c);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setDialogTitle("Select the Cline CLI executable");
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                binaryPathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // Start mode
        c.gridy++;
        c.gridx = 0;
        root.add(new JLabel("Start in:"), c);
        startModeCombo = new JComboBox<>(new String[]{"Act mode", "Plan mode"});
        c.gridx = 1;
        c.weightx = 1;
        root.add(startModeCombo, c);

        // Auto approve
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        root.add(new JLabel("Auto-approve tools:"), c);
        autoApproveCombo = new JComboBox<>(new String[]{"CLI default", "Always on", "Always off"});
        c.gridx = 1;
        c.weightx = 1;
        root.add(autoApproveCombo, c);

        addTextRows(c);
        addBufferControls(c);
        loadSettings();
        return root;
    }

    /** Model / provider / extra arguments rows plus the startup checkbox. */
    private void addTextRows(GridBagConstraints c) {
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        root.add(new JLabel("Model override:"), c);
        modelField = new JBTextField();
        modelField.getEmptyText().setText("Use the model chosen in Cline");
        c.gridx = 1;
        c.weightx = 1;
        root.add(modelField, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        root.add(new JLabel("Provider override:"), c);
        providerField = new JBTextField();
        providerField.getEmptyText().setText("Use the provider chosen in Cline");
        c.gridx = 1;
        c.weightx = 1;
        root.add(providerField, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        root.add(new JLabel("Extra arguments:"), c);
        extraArgsField = new JBTextField();
        extraArgsField.getEmptyText().setText("e.g. --thinking high");
        c.gridx = 1;
        c.weightx = 1;
        root.add(extraArgsField, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 0;
        openOnStartupCheck = new JBCheckBox("Open the Cline panel when a project opens");
        root.add(openOnStartupCheck, c);
    }

    /** Scrollback row (unlimited by default) plus the explanatory note. */
    private void addBufferControls(GridBagConstraints c) {
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        root.add(new JLabel("Terminal scrollback:"), c);

        JPanel bufferRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        unlimitedBufferCheck = new JBCheckBox("Unlimited");
        terminalBufferSpinner = new JSpinner(new SpinnerNumberModel(
                DEFAULT_FINITE_TERMINAL_BUFFER_LINES,
                MIN_TERMINAL_BUFFER_LINES,
                MAX_TERMINAL_BUFFER_LINES,
                1000));
        bufferRow.add(unlimitedBufferCheck);
        bufferRow.add(terminalBufferSpinner);
        bufferRow.add(new JLabel("lines"));
        c.gridx = 1;
        c.weightx = 1;
        root.add(bufferRow, c);

        unlimitedBufferCheck.addActionListener(e ->
                terminalBufferSpinner.setEnabled(!unlimitedBufferCheck.isSelected()));

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        JLabel note = new JLabel("<html><small>No line limit by default. Sets the IDE setting "
                + "<code>terminal.buffer.max.lines.count</code> (shared by all IDE terminals). "
                + "RAM is only used as lines are actually produced.</small></html>");
        root.add(note, c);
    }

    @Override
    public boolean isModified() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        return !binaryPathField.getText().trim().equals(pc.getValue(BINARY_PATH_KEY, ""))
                || !startMode().equals(pc.getValue(START_MODE_KEY, "act"))
                || !autoApprove().equals(pc.getValue(AUTO_APPROVE_KEY, ""))
                || !modelField.getText().trim().equals(pc.getValue(MODEL_KEY, ""))
                || !providerField.getText().trim().equals(pc.getValue(PROVIDER_KEY, ""))
                || !extraArgsField.getText().trim().equals(pc.getValue(EXTRA_ARGS_KEY, ""))
                || openOnStartupCheck.isSelected() != pc.getBoolean(OPEN_ON_STARTUP_KEY, false)
                || selectedTerminalBufferLines() != pc.getInt(TERMINAL_BUFFER_KEY,
                DEFAULT_TERMINAL_BUFFER_LINES);
    }

    @Override
    public void apply() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        boolean launchChanged =
                !binaryPathField.getText().trim().equals(pc.getValue(BINARY_PATH_KEY, ""))
                        || !startMode().equals(pc.getValue(START_MODE_KEY, "act"))
                        || !autoApprove().equals(pc.getValue(AUTO_APPROVE_KEY, ""))
                        || !modelField.getText().trim().equals(pc.getValue(MODEL_KEY, ""))
                        || !providerField.getText().trim().equals(pc.getValue(PROVIDER_KEY, ""))
                        || !extraArgsField.getText().trim().equals(pc.getValue(EXTRA_ARGS_KEY, ""))
                        || selectedTerminalBufferLines() != pc.getInt(TERMINAL_BUFFER_KEY,
                        DEFAULT_TERMINAL_BUFFER_LINES);

        pc.setValue(BINARY_PATH_KEY, binaryPathField.getText().trim());
        pc.setValue(START_MODE_KEY, startMode());
        pc.setValue(AUTO_APPROVE_KEY, autoApprove());
        pc.setValue(MODEL_KEY, modelField.getText().trim());
        pc.setValue(PROVIDER_KEY, providerField.getText().trim());
        pc.setValue(EXTRA_ARGS_KEY, extraArgsField.getText().trim());
        pc.setValue(OPEN_ON_STARTUP_KEY, openOnStartupCheck.isSelected());
        pc.setValue(TERMINAL_BUFFER_KEY, selectedTerminalBufferLines(),
                DEFAULT_TERMINAL_BUFFER_LINES);

        ClineService svc = ServiceManager.getService(project, ClineService.class);
        svc.resetCachedBinary();
        // These values are consumed when the TUI process is launched and the
        // scrollback cap is fixed when the terminal buffer is created, so a
        // running TUI has to be restarted for a change to take effect.
        if (launchChanged && svc.hasTerminalUI()) svc.restartTerminal();
    }

    @Override
    public void reset() {
        loadSettings();
    }

    private String startMode() {
        return startModeCombo != null && startModeCombo.getSelectedIndex() == 1 ? "plan" : "act";
    }

    private String autoApprove() {
        if (autoApproveCombo == null) return "";
        return switch (autoApproveCombo.getSelectedIndex()) {
            case 1 -> "true";
            case 2 -> "false";
            default -> "";
        };
    }

    private int selectedTerminalBufferLines() {
        if (unlimitedBufferCheck != null && unlimitedBufferCheck.isSelected()) {
            return UNLIMITED_TERMINAL_BUFFER_LINES;
        }
        Object v = terminalBufferSpinner == null ? null : terminalBufferSpinner.getValue();
        return v == null ? DEFAULT_FINITE_TERMINAL_BUFFER_LINES : ((Number) v).intValue();
    }

    private void loadSettings() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        binaryPathField.setText(pc.getValue(BINARY_PATH_KEY, ""));
        startModeCombo.setSelectedIndex("plan".equals(pc.getValue(START_MODE_KEY, "act")) ? 1 : 0);
        String auto = pc.getValue(AUTO_APPROVE_KEY, "");
        autoApproveCombo.setSelectedIndex("true".equals(auto) ? 1 : "false".equals(auto) ? 2 : 0);
        modelField.setText(pc.getValue(MODEL_KEY, ""));
        providerField.setText(pc.getValue(PROVIDER_KEY, ""));
        extraArgsField.setText(pc.getValue(EXTRA_ARGS_KEY, ""));
        openOnStartupCheck.setSelected(pc.getBoolean(OPEN_ON_STARTUP_KEY, false));

        int lines = pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);
        boolean unlimited = lines <= 0;
        unlimitedBufferCheck.setSelected(unlimited);
        terminalBufferSpinner.setEnabled(!unlimited);
        terminalBufferSpinner.setValue(unlimited ? DEFAULT_FINITE_TERMINAL_BUFFER_LINES : lines);
    }

    @Override
    public String getDisplayName() {
        return "Cline";
    }
}
