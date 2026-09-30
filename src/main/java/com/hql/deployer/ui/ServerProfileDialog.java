package com.hql.deployer.ui;

import com.hql.deployer.config.PasswordStore;
import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.ssh.JschSshSession;
import com.hql.deployer.ssh.SshSession;
import com.hql.deployer.util.DeployBundle;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * 单台服务器的编辑对话框。
 *
 * <p>密码输入框留空表示「不修改已保存的密码」——服务器列表里永远不展示明文密码，
 * 用户也无从知道当前密码是什么，因此空值不能被当成「清空密码」。</p>
 *
 * @author hql on 2026/9/28
 */
public final class ServerProfileDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(ServerProfileDialog.class);

    private final boolean isNew;
    private final String editingId;
    private final String initialPassword;

    private final JBTextField nameField = new JBTextField();
    private final JBTextField hostListField = new JBTextField();
    private final JBTextField portField = new JBTextField("22");
    private final JBTextField usernameField = new JBTextField("root");
    private final JBPasswordField passwordField = new JBPasswordField();
    private final JBTextField baseDirField = new JBTextField();
    private final JBTextField connectTimeoutField = new JBTextField("10000");
    private final JBTextField execTimeoutField = new JBTextField("60000");
    private final JBTextField uploadTimeoutField = new JBTextField("300000");
    private final JBTextField backupKeepField = new JBTextField("3");
    private final JBLabel testResultLabel = new JBLabel(" ");

    /**
     * 构造编辑对话框。
     *
     * @param profile 待编辑配置；新建时传入一个已填好 id 的空对象
     * @param isNew   是否为新建
     */
    public ServerProfileDialog(@NotNull ServerProfile profile, boolean isNew) {
        super(true);
        this.isNew = isNew;
        this.editingId = profile.getId();
        this.initialPassword = PasswordStore.getPassword(profile.getId());
        LOG.info("打开服务器对话框: id=" + profile.getId() + ", name=" + profile.getName()
                + ", initialPassword为空=" + initialPassword.isEmpty());

        setTitle(DeployBundle.message("dialog.host.title"));
        nameField.setText(profile.getName());
        hostListField.setText(profile.getHostList());
        portField.setText(String.valueOf(profile.getPort()));
        usernameField.setText(profile.getUsername());
        baseDirField.setText(profile.getRemoteBaseDir());
        connectTimeoutField.setText(String.valueOf(profile.getConnectTimeoutMs()));
        execTimeoutField.setText(String.valueOf(profile.getExecTimeoutMs()));
        uploadTimeoutField.setText(String.valueOf(profile.getUploadTimeoutMs()));
        backupKeepField.setText(String.valueOf(profile.getBackupKeepCount()));
        init();
    }

    @Override
    @Nullable
    protected JComponent createCenterPanel() {
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(DeployBundle.message("dialog.host.name") + ":", nameField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.hostList") + ":", hostListField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.port") + ":", portField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.username") + ":", usernameField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.password") + ":", passwordField, 1, false)
                .addComponentToRightColumn(
                        new JBLabel("<html><small>" + DeployBundle.message("dialog.host.password.tip")
                                + "</small></html>"), 0)
                .addLabeledComponent(DeployBundle.message("dialog.host.baseDir") + ":", baseDirField, 1, false)
                .addSeparator()
                .addLabeledComponent(DeployBundle.message("dialog.host.connectTimeout") + ":",
                        connectTimeoutField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.execTimeout") + ":",
                        execTimeoutField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.uploadTimeout") + ":",
                        uploadTimeoutField, 1, false)
                .addLabeledComponent(DeployBundle.message("dialog.host.backupKeepCount") + ":",
                        backupKeepField, 1, false)
                .addComponentToRightColumn(
                        new JBLabel("<html><small>" + DeployBundle.message("dialog.host.backupKeepCount.tip")
                                + "</small></html>"), 0)
                .addSeparator()
                .addComponentToRightColumn(createTestButton(), 0)
                .addComponent(testResultLabel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        panel.setBorder(JBUI.Borders.empty(10));
        panel.setPreferredSize(new java.awt.Dimension(520, 0));
        return panel;
    }

    /**
     * 「测试连接」按钮。测试只读当前输入，不会保存任何配置。
     */
    private JComponent createTestButton() {
        JButton button = new JButton(DeployBundle.message("dialog.host.testConnection"));
        button.addActionListener(e -> {
            ValidationInfo error = doValidate();
            if (error != null) {
                return;
            }
            testResultLabel.setText(DeployBundle.message("dialog.host.testing"));
            testConnection();
        });
        return button;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return isNew ? nameField : hostListField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (nameField.getText().trim().isEmpty()) {
            return new ValidationInfo(DeployBundle.message("dialog.host.name") + " 不能为空", nameField);
        }
        if (hostListField.getText().trim().isEmpty()) {
            return new ValidationInfo(DeployBundle.message("dialog.host.hostList") + " 不能为空", hostListField);
        }
        if (parseInt(portField.getText(), -1) <= 0) {
            return new ValidationInfo(DeployBundle.message("dialog.host.port") + " 不合法", portField);
        }
        if (usernameField.getText().trim().isEmpty()) {
            return new ValidationInfo(DeployBundle.message("dialog.host.username") + " 不能为空", usernameField);
        }
        return null;
    }

    /**
     * 把界面上的值写回配置对象。密码为空时保持原值不变。
     */
    public void applyTo(@NotNull ServerProfile profile) {
        applyTo(profile, true);
    }

    /**
     * 把界面上的值写回配置对象。
     *
     * @param profile        目标配置
     * @param persistPassword 是否把本次输入的密码写入 PasswordSafe。
     *                        预览用途必须传 {@code false}，否则会为临时 id
     *                        留下一份永远用不到的密码
     */
    private void applyTo(@NotNull ServerProfile profile, boolean persistPassword) {
        profile.setName(nameField.getText().trim());
        profile.setHostList(hostListField.getText().trim());
        profile.setPort(parseInt(portField.getText(), 22));
        profile.setUsername(usernameField.getText().trim());
        profile.setRemoteBaseDir(baseDirField.getText().trim());
        profile.setConnectTimeoutMs(parseInt(connectTimeoutField.getText(), 10_000));
        profile.setExecTimeoutMs(parseInt(execTimeoutField.getText(), 60_000));
        profile.setUploadTimeoutMs(parseInt(uploadTimeoutField.getText(), 300_000));
        profile.setBackupKeepCount(parseInt(backupKeepField.getText(), 3));

        char[] password = passwordField.getPassword();
        if (password != null && password.length > 0) {
            if (persistPassword) {
                PasswordStore.setPassword(profile.getId(), new String(password));
            }
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * 供「测试连接」使用：取当前输入组装临时配置，不落库、不写密码。
     *
     * <p>沿用被编辑服务器的 id，这样预览对象与真实配置的凭据一一对应；
     * 若这里生成新 id，任何按 id 取密码的后续代码都会读到空值。</p>
     */
    @NotNull
    public ServerProfile toProfile() {
        ServerProfile profile = new ServerProfile();
        profile.setId(editingId);
        applyTo(profile, false);
        return profile;
    }

    /**
     * 测试连接是否可用，结果直接回填到对话框内。
     *
     * <p>密码优先用本次输入，未输入时回退到已保存的密码。测试不会保存任何配置，
     * 因此用户可以先试通再决定是否点确定。</p>
     */
    public void testConnection() {
        ServerProfile profile = toProfile();
        char[] typed = passwordField.getPassword();
        String password = typed != null && typed.length > 0 ? new String(typed) : initialPassword;
        if (typed != null) {
            java.util.Arrays.fill(typed, '\0');
        }
        LOG.info("测试连接: id=" + profile.getId() + ", 使用输入框密码=" + (typed != null && typed.length > 0)
                + ", 最终密码为空=" + password.isEmpty());

        ProgressManager.getInstance().run(new Task.Backgroundable(
                null, DeployBundle.message("dialog.host.testConnection"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String message;
                boolean success;
                try (SshSession session = new JschSshSession()) {
                    String host = session.connect(profile, password);
                    success = true;
                    message = DeployBundle.message("dialog.host.testSuccess") + ": " + host;
                } catch (Exception e) {
                    success = false;
                    message = DeployBundle.message("dialog.host.testFailed") + ": " + e.getMessage();
                }
                String finalMessage = message;
                boolean finalSuccess = success;
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    testResultLabel.setText(finalMessage);
                    testResultLabel.setForeground(finalSuccess
                            ? com.intellij.ui.JBColor.GREEN
                            : com.intellij.ui.JBColor.RED);
                });
            }
        });
    }

    private static int parseInt(@Nullable String text, int defaultValue) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
