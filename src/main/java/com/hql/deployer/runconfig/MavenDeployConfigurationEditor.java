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
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * 目标主机多选，用多列表格展示：勾选列 + 名称/主机/端口/用户名/远端目录。
     * 服务器多时也能一眼分辨每台服务器的地址与归属，勾选即代表一次部署目标。
     */
    private final HostTableModel hostTableModel = new HostTableModel();
    private final JBTable hostTable = new JBTable(hostTableModel);
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
        copyUiToConfiguration(configuration);
    }

    @Override
    public void checkEditorData(MavenDeployRunConfiguration configuration) {
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

        List<ServerProfile> hosts = hostTableModel.getCheckedServers();
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
        // 表格：单击勾选列或按空格即切换该行选中
        hostTable.setRowHeight(22);
        hostTable.setShowGrid(false);
        hostTable.setStriped(true);
        hostTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        hostTable.getColumnModel().getColumn(0).setMaxWidth(36);
        hostTable.getColumnModel().getColumn(0).setResizable(false);
        // 预设列宽：主机地址与远端目录按内容量给足宽度，配合不自动压缩，
        // 保证多服务器时每台的地址、用户名、目录都能完整看清楚
        int[] columnWidths = {36, 110, 190, 56, 90, 240};
        for (int i = 0; i < columnWidths.length; i++) {
            hostTable.getColumnModel().getColumn(i).setPreferredWidth(columnWidths[i]);
        }
        hostTable.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_OFF);
        hostTable.setFillsViewportHeight(true);
        hostTable.getEmptyText().setText("暂无服务器，请到 设置 → 工具 → Deploy to Host 添加");
        hostTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = hostTable.columnAtPoint(e.getPoint());
                int row = hostTable.rowAtPoint(e.getPoint());
                if (col == 0 && row >= 0) {
                    hostTableModel.toggle(row);
                    onHostSelectionChanged();
                }
            }
        });
        hostTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleHostChecked");
        hostTable.getActionMap().put("toggleHostChecked", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = hostTable.getSelectedRow();
                if (row >= 0) {
                    hostTableModel.toggle(row);
                    onHostSelectionChanged();
                }
            }
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

        JScrollPane hostScroll = new JScrollPane(hostTable);
        // 最小高度兜底：表单其它字段再高，也不会把服务器表格压到看不清
        hostScroll.setPreferredSize(new java.awt.Dimension(0, 220));
        hostScroll.setMinimumSize(new java.awt.Dimension(0, 220));
        hostScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // 「目标主机」标题行右侧提供「全选 / 全不选」，服务器多时免去逐行勾选
        JPanel hostHeader = new JPanel(new BorderLayout());
        hostHeader.add(new JBLabel("<html><b>目标主机</b></html>"), BorderLayout.WEST);
        JPanel hostLinks = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        hostLinks.add(createLinkLabel("全选", this::selectAllHosts));
        hostLinks.add(createLinkLabel("全不选", this::clearAllHosts));
        hostHeader.add(hostLinks, BorderLayout.EAST);

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
                .addComponent(hostHeader)
                // 表格区域吃掉面板的全部垂直余量：对话框越大，能同时看到的服务器越多
                .addComponentFillVertically(hostScroll, 0)
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
                .getPanel();
        contentPanel.setBorder(JBUI.Borders.empty(10));
        // 面板自然高度约 790px（各分组字段 + 表格 220px）；给足高度，
        // 避免 GridBag 布局把服务器表格压缩到只剩一两行
        contentPanel.setPreferredSize(new java.awt.Dimension(720, 880));
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
        List<ServerProfile> hosts = hostTableModel.getCheckedServers();
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
                : hostTableModel.getCheckedServers().stream()
                        .map(ServerProfile::getId)
                        .collect(java.util.stream.Collectors.toList());
        hostTableModel.setRows(ServerProfileService.getInstance().getServers(), new java.util.HashSet<>(checked));
    }

    /**
     * 勾选状态变化后的统一处理：刷新目标目录提示，并通知 IDE 配置已修改。
     */
    private void onHostSelectionChanged() {
        updateResolvedTarget();
        fireEditorStateChanged();
    }

    /**
     * 主机地址文案：多台时只保留首台 + 「等 N 台」，避免单行塞满被截断。
     */
    @NotNull
    private static String hostLabel(@NotNull ServerProfile profile) {
        List<String> hosts = profile.getHosts();
        if (hosts.isEmpty()) {
            return "";
        }
        if (hosts.size() == 1) {
            return hosts.get(0);
        }
        return hosts.get(0) + " 等 " + hosts.size() + " 台";
    }

    /**
     * 生成一个可点击的文本链接（用 {@link JBLabel} 实现，避免依赖平台组件包的版本差异）。
     */
    @NotNull
    private static JBLabel createLinkLabel(@NotNull String text, @NotNull Runnable action) {
        JBLabel label = new JBLabel("<html><a href='#'>" + text + "</a></html>");
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        });
        return label;
    }

    /**
     * 全选服务器。服务器较多时一键勾选全部目标，不必逐行点击。
     */
    private void selectAllHosts() {
        hostTableModel.setAllChecked(true);
        onHostSelectionChanged();
    }

    /**
     * 清空已勾选的服务器。
     */
    private void clearAllHosts() {
        hostTableModel.setAllChecked(false);
        onHostSelectionChanged();
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
     * 服务器表格模型。第 0 列为勾选列，其余列依次为名称、主机、端口、用户名、远端目录。
     * 顺序与 {@link ServerProfileService} 保持一致。
     */
    private static final class HostTableModel extends AbstractTableModel {

        private static final String[] TITLES = {"", "名称", "主机", "端口", "用户名", "远端目录"};

        private final List<ServerProfile> servers = new ArrayList<>();
        private boolean[] checked = new boolean[0];

        void setRows(@NotNull List<ServerProfile> servers, @NotNull Set<String> checkedIds) {
            this.servers.clear();
            this.servers.addAll(servers);
            this.checked = new boolean[servers.size()];
            for (int i = 0; i < servers.size(); i++) {
                this.checked[i] = checkedIds.contains(servers.get(i).getId());
            }
            fireTableDataChanged();
        }

        /** 切换某行勾选状态。 */
        void toggle(int row) {
            if (row < 0 || row >= checked.length) {
                return;
            }
            checked[row] = !checked[row];
            fireTableRowsUpdated(row, row);
        }

        void setAllChecked(boolean value) {
            for (int i = 0; i < checked.length; i++) {
                checked[i] = value;
            }
            fireTableDataChanged();
        }

        /** 已勾选的服务器，保持表格顺序。 */
        @NotNull
        List<ServerProfile> getCheckedServers() {
            List<ServerProfile> result = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) {
                    result.add(servers.get(i));
                }
            }
            return result;
        }

        @Override
        public int getRowCount() {
            return servers.size();
        }

        @Override
        public int getColumnCount() {
            return TITLES.length;
        }

        @Override
        public String getColumnName(int column) {
            return TITLES[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ServerProfile profile = servers.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> checked[rowIndex];
                case 1 -> profile.getName();
                case 2 -> hostLabel(profile);
                case 3 -> String.valueOf(profile.getPort());
                case 4 -> profile.getUsername() == null ? "" : profile.getUsername();
                case 5 -> profile.getRemoteBaseDir() == null ? "" : profile.getRemoteBaseDir();
                default -> "";
            };
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
