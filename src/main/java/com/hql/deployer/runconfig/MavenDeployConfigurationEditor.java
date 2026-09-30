package com.hql.deployer.runconfig;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.config.ServerProfileService;
import com.hql.deployer.maven.MavenModule;
import com.hql.deployer.maven.MavenModuleScanner;
import com.hql.deployer.ui.LocalPathSelector;
import com.hql.deployer.util.DeployBundle;
import com.intellij.execution.impl.CheckableRunConfigurationEditor;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「部署到服务器」配置的编辑面板。
 *
 * <p>分五组，顺序与 Alibaba Cloud Toolkit 一致：部署内容 → 目标主机 → 上传前命令 → 部署后命令 →
 * 高级。
 * 「部署内容」的三个模式共用同一组字段，切换模式时只启用相关控件，避免用户填错位置。</p>
 *
 * @author hql on 2026/9/28
 */
public final class MavenDeployConfigurationEditor extends SettingsEditor<MavenDeployRunConfiguration>
        implements CheckableRunConfigurationEditor<MavenDeployRunConfiguration> {

    private final Project project;

    private final ComboBox<BuildMode> buildModeCombo = new ComboBox<>(BuildMode.values());
    private final ComboBox<String> moduleCombo = new ComboBox<>();
    private final JBTextField mavenGoalsField = new JBTextField();
    private final JBCheckBox skipTestsCheck = new JBCheckBox("跳过测试 (-DskipTests)");

    private final JBTextField customCommandField = new JBTextField();
    private final JBTextField customWorkingDirField = new JBTextField();

    private final LocalPathSelector localPathSelector;
    private final JBTextField excludesField = new JBTextField();
    private final JBTextField chmodField = new JBTextField();

    /**
     * 目标主机多选。每行一个 {@link ServerProfile}，勾选即代表一次部署目标。
     */
    private final CheckBoxList<ServerProfile> hostList = new CheckBoxList<>();
    private final JBTextField targetDirectoryField = new JBTextField();
    private final JBLabel resolvedTargetLabel = new JBLabel();

    private final JBTextField afterCommandField = new JBTextField();

    private final JBTextField preCommandField = new JBTextField();

    private final JBCheckBox stagingCheck = new JBCheckBox("先上传到远端暂存目录，全部成功后再原子切换（推荐）");
    private final JBTextField packageTimeoutField = new JBTextField();

    public MavenDeployConfigurationEditor(@Nullable Project project) {
        this.project = project;
        // 字段初始化早于构造器赋值，selector 依赖 project，只能在这里创建
        this.localPathSelector = new LocalPathSelector(project);
    }

    @Override
    protected void resetEditorFrom(@NotNull MavenDeployRunConfiguration configuration) {
        configuration.applyDefaults();
        MavenDeployRunConfiguration.Options o = configuration.getDeployOptions();

        buildModeCombo.setSelectedItem(configuration.resolveBuildMode());
        reloadModules();
        moduleCombo.setSelectedItem(o.mavenModulePath);
        mavenGoalsField.setText(o.mavenGoals);
        skipTestsCheck.setSelected(o.skipTests);
        customCommandField.setText(o.customCommand);
        customWorkingDirField.setText(o.customWorkingDir);
        localPathSelector.setPath(o.localPath);
        excludesField.setText(o.excludes);
        chmodField.setText(o.chmod);

        reloadHosts(configuration.getSelectedHostIds());
        targetDirectoryField.setText(o.targetDirectory);
        preCommandField.setText(o.preUploadCommand);
        afterCommandField.setText(o.afterDeployCommand);
        stagingCheck.setSelected(o.stagingEnabled);
        packageTimeoutField.setText(String.valueOf(o.packageTimeoutMs));

        updateEnabledState();
        updateResolvedTarget();
    }

    @Override
    protected void applyEditorTo(@NotNull MavenDeployRunConfiguration configuration) {
        if (Boolean.getBoolean("mavenDeploy.rcDiag")) {
            StringBuilder sb = new StringBuilder("[rc-diag] applyEditorTo id=")
                    .append(System.identityHashCode(configuration))
                    .append(" goals=").append(configuration.getDeployOptions().mavenGoals)
                    .append('\n');
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(24, st.length); i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
            System.out.println(sb);
        }
        copyUiToConfiguration(configuration);
    }

    @Override
    public void checkEditorData(MavenDeployRunConfiguration configuration) {
        if (Boolean.getBoolean("mavenDeploy.rcDiag")) {
            StringBuilder sb = new StringBuilder("[rc-diag] checkEditorData id=")
                    .append(System.identityHashCode(configuration))
                    .append('\n');
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(24, st.length); i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
            System.out.println(sb);
        }
        copyUiToConfiguration(configuration);
    }

    /**
     * 把当前 UI 上的值同步到目标配置对象。
     */
    private void copyUiToConfiguration(@NotNull MavenDeployRunConfiguration configuration) {
        MavenDeployRunConfiguration.Options o = configuration.getDeployOptions();

        BuildMode mode = (BuildMode) buildModeCombo.getSelectedItem();
        o.buildMode = (mode == null ? BuildMode.MAVEN_BUILD : mode).name();
        Object selectedModule = moduleCombo.getSelectedItem();
        o.mavenModulePath = selectedModule == null ? "" : String.valueOf(selectedModule);
        o.mavenGoals = mavenGoalsField.getText().trim();
        o.skipTests = skipTestsCheck.isSelected();
        o.customCommand = customCommandField.getText().trim();
        o.customWorkingDir = customWorkingDirField.getText().trim();
        o.localPath = localPathSelector.getPath();
        o.excludes = excludesField.getText().trim();
        o.chmod = chmodField.getText().trim();

        List<ServerProfile> hosts = hostList.getCheckedItems();
        StringBuilder ids = new StringBuilder();
        for (ServerProfile host : hosts) {
            if (ids.length() > 0) {
                ids.append('\n');
            }
            ids.append(host.getId());
        }
        o.hostId = hosts.isEmpty() ? "" : hosts.get(0).getId();
        o.hostIds = ids.toString();
        o.targetDirectory = targetDirectoryField.getText().trim();
        o.preUploadCommand = preCommandField.getText().trim();
        o.afterDeployCommand = afterCommandField.getText().trim();
        o.stagingEnabled = stagingCheck.isSelected();
        o.packageTimeoutMs = parseIntOrDefault(packageTimeoutField.getText(), 600_000);
    }

    @Override
    @NotNull
    protected JComponent createEditor() {
        // 每个控件的内容变化都要通知 IDE，否则「应用」按钮一直是置灰的
        buildModeCombo.addActionListener(e -> {
            updateEnabledState();
            fireEditorStateChanged();
        });
        hostList.setCheckBoxListListener((index, value) -> {
            updateResolvedTarget();
            fireEditorStateChanged();
        });
        targetDirectoryField.getDocument().addDocumentListener(
                new SimpleDocumentListener(() -> {
                    updateResolvedTarget();
                    fireEditorStateChanged();
                }));

        moduleCombo.setEditable(true);
        moduleCombo.setMaximumRowCount(24);
        moduleCombo.addActionListener(e -> fireEditorStateChanged());
        bindChangeListener(mavenGoalsField);
        bindChangeListener(customCommandField);
        bindChangeListener(customWorkingDirField);
        bindChangeListener(excludesField);
        bindChangeListener(chmodField);
        bindChangeListener(preCommandField);
        bindChangeListener(afterCommandField);
        bindChangeListener(packageTimeoutField);
        skipTestsCheck.addActionListener(e -> fireEditorStateChanged());
        stagingCheck.addActionListener(e -> fireEditorStateChanged());
        // 路径选择器内部只刷新提示，文本变化在这里转成「配置已修改」通知
        JTextField pathTextField = ((TextFieldWithBrowseButton) localPathSelector.getFieldComponent())
                .getTextField();
        pathTextField.getDocument().addDocumentListener(new SimpleDocumentListener(this::fireEditorStateChanged));
        // 可编辑下拉框直接输入时也会触发输入框的文档变化
        JTextField moduleEditor = (JTextField) moduleCombo.getEditor().getEditorComponent();
        moduleEditor.getDocument().addDocumentListener(new SimpleDocumentListener(this::fireEditorStateChanged));

        JScrollPane hostScroll = new JScrollPane(hostList);
        hostScroll.setPreferredSize(new java.awt.Dimension(0, 96));
        hostScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel contentPanel = FormBuilder.createFormBuilder()
                // ---------- 部署内容 ----------
                .addComponent(new JBLabel("<html><b>部署内容</b></html>"))
                .addLabeledComponent("部署方式:", buildModeCombo, 1, false)
                .addLabeledComponent("Maven 模块:", moduleCombo, 1, false)
                .addLabeledComponent("Goals:", mavenGoalsField, 1, false)
                .addComponentToRightColumn(skipTestsCheck, 0)
                .addLabeledComponent("自定义命令:", customCommandField, 1, false)
                .addLabeledComponent("命令工作目录:", customWorkingDirField, 1, false)
                .addLabeledComponent("待上传路径:", localPathSelector, 1, false)
                .addLabeledComponent("排除规则:", excludesField, 1, false)
                .addLabeledComponent("远端权限:", chmodField, 1, false)
                // ---------- 目标主机 ----------
                .addSeparator()
                .addComponent(new JBLabel("<html><b>目标主机</b></html>"))
                .addLabeledComponent("服务器:", hostScroll, 1, false)
                .addLabeledComponent("目标目录:", targetDirectoryField, 1, false)
                .addComponentToRightColumn(resolvedTargetLabel, 0)
                // ---------- 上传前命令 ----------
                .addSeparator()
                .addComponent(new JBLabel("<html><b>上传前命令</b></html>"))
                .addLabeledComponent("远端命令:", preCommandField, 1, false)
                // ---------- 部署后命令 ----------
                .addSeparator()
                .addComponent(new JBLabel("<html><b>部署后命令</b></html>"))
                .addLabeledComponent("远端命令:", afterCommandField, 1, false)
                // ---------- 高级 ----------
                .addSeparator()
                .addComponent(new JBLabel("<html><b>高级</b></html>"))
                .addComponentToRightColumn(stagingCheck, 0)
                .addLabeledComponent("构建超时(毫秒):", packageTimeoutField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        contentPanel.setBorder(JBUI.Borders.empty(10));
        contentPanel.setPreferredSize(new java.awt.Dimension(720, 700));
        return contentPanel;
    }

    /**
     * 按部署方式启用/禁用无关控件，降低填错概率。
     */
    private void updateEnabledState() {
        BuildMode mode = (BuildMode) buildModeCombo.getSelectedItem();
        boolean maven = mode == BuildMode.MAVEN_BUILD;
        boolean custom = mode == BuildMode.CUSTOM_COMMAND;
        moduleCombo.setEnabled(maven);
        mavenGoalsField.setEnabled(maven);
        skipTestsCheck.setEnabled(maven);
        customCommandField.setEnabled(custom);
        customWorkingDirField.setEnabled(custom);
    }

    /**
     * 实时显示目标目录拼接后的绝对路径，减少用户对「相对谁」的困惑。
     * 多台时汇总已勾选数量与主机名。
     */
    private void updateResolvedTarget() {
        List<ServerProfile> hosts = hostList.getCheckedItems();
        String target = targetDirectoryField.getText().trim();
        if (hosts.isEmpty()) {
            resolvedTargetLabel.setText(" ");
            return;
        }
        String base = hosts.get(0).getRemoteBaseDir() == null ? "" : hosts.get(0).getRemoteBaseDir().trim();
        String resolved = com.hql.deployer.util.PathUtil.joinRemote(base, target);
        String hostText = hosts.size() == 1
                ? hosts.get(0).getName()
                : hosts.size() + " 台主机";
        resolvedTargetLabel.setText(resolved.isEmpty() ? " " : "将部署到 " + hostText + ": " + resolved
                + (hosts.size() > 1 ? "（目标目录按各自远端基目录拼接）" : ""));
    }

    /**
     * 文本字段内容变化时通知 IDE 配置已修改。
     */
    private void bindChangeListener(@NotNull JBTextField field) {
        field.getDocument().addDocumentListener(new SimpleDocumentListener(this::fireEditorStateChanged));
    }

    /**
     * 重新扫描项目内的 Maven 模块。
     */
    private void reloadModules() {
        Object previous = moduleCombo.getSelectedItem();
        List<String> paths = new ArrayList<>();
        paths.add("");
        File root = resolveProjectRoot();
        if (root != null) {
            for (MavenModule module : new MavenModuleScanner(root).scan()) {
                String relative = com.hql.deployer.util.PathUtil.relativeTo(root, module.moduleDir());
                if (relative != null && !relative.isEmpty()) {
                    paths.add(relative);
                }
            }
        }
        moduleCombo.removeAllItems();
        for (String path : paths) {
            moduleCombo.addItem(path);
        }
        if (previous != null) {
            moduleCombo.setSelectedItem(previous);
        }
    }

    /**
     * 重新加载服务器列表并勾选指定 id 的主机。
     *
     * @param selectedIds 需要勾选的服务器 id；{@code null} 时保留当前勾选状态
     */
    private void reloadHosts(@Nullable List<String> selectedIds) {
        List<String> checked = selectedIds != null
                ? selectedIds
                : hostList.getCheckedItems().stream()
                        .map(ServerProfile::getId)
                        .collect(java.util.stream.Collectors.toList());
        hostList.clear();
        for (ServerProfile profile : ServerProfileService.getInstance().getServers()) {
            hostList.addItem(profile, profile.getName(), checked.contains(profile.getId()));
        }
    }

    @Nullable
    private File resolveProjectRoot() {
        if (project == null) {
            return null;
        }
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            return new File(basePath);
        }
        com.intellij.openapi.vfs.VirtualFile projectFile = project.getProjectFile();
        if (projectFile == null || projectFile.getParent() == null) {
            return null;
        }
        return com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(projectFile.getParent());
    }

    private static int parseIntOrDefault(@Nullable String text, int defaultValue) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 只关心「内容变化」的场景，用不到完整 DocumentListener 适配器。
     */
    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {

        private final Runnable onChange;

        private SimpleDocumentListener(@NotNull Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }
    }
}
