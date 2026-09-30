package com.hql.deployer.ui;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.config.ServerProfileService;
import com.hql.deployer.util.DeployBundle;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：管理服务器列表与全局默认值。
 *
 * <p>服务器列表用表格 + 工具栏（新增/编辑/删除），编辑走 {@link ServerProfileDialog}，
 * 密码不进入表格，只在对话框里设置。</p>
 *
 * @author hql on 2026/9/28
 */
public final class DeployConfigurable implements Configurable {

    private final ServerProfileTableModel tableModel = new ServerProfileTableModel();
    private final JBTable table = new JBTable(tableModel);

    private final com.intellij.ui.components.JBTextField mavenExecutableField =
            new com.intellij.ui.components.JBTextField();
    private final com.intellij.ui.components.JBTextField packageTimeoutField =
            new com.intellij.ui.components.JBTextField();
    private final com.intellij.ui.components.JBTextField connectTimeoutField =
            new com.intellij.ui.components.JBTextField();
    private final com.intellij.ui.components.JBTextField execTimeoutField =
            new com.intellij.ui.components.JBTextField();
    private final com.intellij.ui.components.JBCheckBox stagingCheck =
            new com.intellij.ui.components.JBCheckBox(
                    DeployBundle.message("settings.default.staging"));
    private final com.intellij.ui.components.JBCheckBox notifyCheck =
            new com.intellij.ui.components.JBCheckBox("部署结束后弹出通知");

    private JPanel rootPanel;

    @Override
    @Nls
    public String getDisplayName() {
        return DeployBundle.message("plugin.name");
    }

    @Override
    public @Nullable JComponent createComponent() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                table.getEmptyText().setText(DeployBundle.message("label.noHosts"));
            }
        });

        JPanel tablePanel = ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> addServer())
                .setEditAction(button -> editSelectedServer())
                .setRemoveAction(button -> removeSelectedServer())
                .disableUpDownActions()
                .createPanel();
        tablePanel.setPreferredSize(new java.awt.Dimension(640, 220));

        JPanel defaultsPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(DeployBundle.message("settings.default.buildCommand") + ":",
                        mavenExecutableField, 1, false)
                .addComponentToRightColumn(
                        new JBLabel("<html><small>" + DeployBundle.message("settings.default.buildCommand.tip")
                                + "</small></html>"), 0)
                .addLabeledComponent(DeployBundle.message("settings.default.packageTimeout") + ":",
                        packageTimeoutField, 1, false)
                .addLabeledComponent(DeployBundle.message("settings.default.connectTimeout") + ":",
                        connectTimeoutField, 1, false)
                .addLabeledComponent(DeployBundle.message("settings.default.execTimeout") + ":",
                        execTimeoutField, 1, false)
                .addComponent(stagingCheck)
                .addComponent(notifyCheck)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        JPanel noticePanel = new JPanel(new java.awt.BorderLayout());
        noticePanel.add(new JBLabel("<html><small>" + DeployBundle.message("settings.default.notice")
                + "</small></html>"), java.awt.BorderLayout.WEST);

        rootPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("<html><b>" + DeployBundle.message("settings.servers")
                        + "</b></html>"))
                .addComponentFillVertically(tablePanel, 1)
                .addSeparator()
                .addComponent(new JBLabel("<html><b>" + DeployBundle.message("settings.defaults")
                        + "</b></html>"))
                .addComponent(defaultsPanel)
                .addComponent(noticePanel)
                .getPanel();
        rootPanel.setBorder(JBUI.Borders.empty(10));
        return rootPanel;
    }

    @Override
    public void reset() {
        ServerProfileService service = ServerProfileService.getInstance();
        tableModel.setServers(new ArrayList<>(service.getServers()));
        ServerProfileService.State state = service.getState();
        mavenExecutableField.setText(nullToEmpty(state.mavenExecutable));
        packageTimeoutField.setText(String.valueOf(state.packageTimeoutMs));
        connectTimeoutField.setText(String.valueOf(state.connectTimeoutMs));
        execTimeoutField.setText(String.valueOf(state.execTimeoutMs));
        stagingCheck.setSelected(state.stagingEnabled);
        notifyCheck.setSelected(state.notifyEnabled);
    }

    @Override
    public boolean isModified() {
        ServerProfileService service = ServerProfileService.getInstance();
        ServerProfileService.State state = service.getState();
        return !tableModel.getServers().equals(service.getServers())
                || !mavenExecutableField.getText().trim().equals(nullToEmpty(state.mavenExecutable))
                || !packageTimeoutField.getText().equals(String.valueOf(state.packageTimeoutMs))
                || !connectTimeoutField.getText().equals(String.valueOf(state.connectTimeoutMs))
                || !execTimeoutField.getText().equals(String.valueOf(state.execTimeoutMs))
                || stagingCheck.isSelected() != state.stagingEnabled
                || notifyCheck.isSelected() != state.notifyEnabled;
    }

    @Override
    public void apply() throws ConfigurationException {
        ServerProfileService service = ServerProfileService.getInstance();

        // 先删除后重加，保证顺序与用户在表格中看到的一致
        List<ServerProfile> edited = tableModel.getServers();
        List<ServerProfile> existing = new ArrayList<>(service.getServers());
        for (ServerProfile profile : existing) {
            if (edited.stream().noneMatch(p -> p.getId().equals(profile.getId()))) {
                service.removeServer(profile);
            }
        }
        List<ServerProfile> result = new ArrayList<>();
        for (ServerProfile profile : edited) {
            // 存一份副本，避免表格里的实例与已持久化的配置共用同一个对象。
            // 逐字段搬运曾经漏过新加的字段，导致用户一点「应用」就把配置静默重置成默认值；
            // 克隆不会漏，新字段自动跟随。
            result.add(profile.clone());
        }
        service.replaceServers(result);

        ServerProfileService.State state = service.getState();
        state.mavenExecutable = mavenExecutableField.getText().trim();
        state.packageTimeoutMs = parsePositive(packageTimeoutField.getText(), 600_000);
        state.connectTimeoutMs = parsePositive(connectTimeoutField.getText(), 10_000);
        state.execTimeoutMs = parsePositive(execTimeoutField.getText(), 60_000);
        state.stagingEnabled = stagingCheck.isSelected();
        state.notifyEnabled = notifyCheck.isSelected();
    }

    // ------------------------------------------------------------------ 表格操作

    private void addServer() {
        ServerProfileService service = ServerProfileService.getInstance();
        ServerProfile profile = service.addServer(null);
        ServerProfileDialog dialog = new ServerProfileDialog(profile, true);
        if (dialog.showAndGet()) {
            dialog.applyTo(profile);
            tableModel.add(profile);
        } else {
            service.removeServer(profile);
        }
    }

    private void editSelectedServer() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        ServerProfile profile = tableModel.getServers().get(row);
        ServerProfileDialog dialog = new ServerProfileDialog(profile, false);
        if (dialog.showAndGet()) {
            dialog.applyTo(profile);
            tableModel.refresh();
        }
    }

    private void removeSelectedServer() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        tableModel.removeRow(row);
    }

    // ------------------------------------------------------------------ 辅助

    @NotNull
    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static int parsePositive(@Nullable String text, int defaultValue) {
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
     * 服务器表格模型。密码不在其中。
     */
    private static final class ServerProfileTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                DeployBundle.message("dialog.host.name"),
                DeployBundle.message("dialog.host.hostList"),
                DeployBundle.message("dialog.host.port"),
                DeployBundle.message("dialog.host.username"),
                DeployBundle.message("dialog.host.baseDir")
        };

        private final List<ServerProfile> servers = new ArrayList<>();

        void setServers(@NotNull List<ServerProfile> servers) {
            this.servers.clear();
            this.servers.addAll(servers);
            fireTableDataChanged();
        }

        @NotNull
        List<ServerProfile> getServers() {
            return servers;
        }

        void add(@NotNull ServerProfile profile) {
            servers.add(profile);
            fireTableRowsInserted(servers.size() - 1, servers.size() - 1);
        }

        void removeRow(int index) {
            if (index < 0 || index >= servers.size()) {
                return;
            }
            servers.remove(index);
            fireTableRowsDeleted(index, index);
        }

        void refresh() {
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return servers.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ServerProfile profile = servers.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> profile.getName();
                case 1 -> String.join(", ", profile.getHosts());
                case 2 -> profile.getPort();
                case 3 -> profile.getUsername();
                case 4 -> profile.getRemoteBaseDir();
                default -> "";
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
