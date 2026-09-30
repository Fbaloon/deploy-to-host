package com.hql.deployer.ui;

import com.hql.deployer.maven.MavenModule;
import com.hql.deployer.maven.MavenModuleScanner;
import com.hql.deployer.pipeline.DeployTask;
import com.hql.deployer.runconfig.MavenDeployProgramRunner;
import com.hql.deployer.runconfig.MavenDeployRunConfiguration;
import com.hql.deployer.util.DeployBundle;
import com.hql.deployer.util.DeployNotifier;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.List;

/**
 * 「一键部署」工具窗。
 *
 * <p>用法：选 Maven 模块 → 选已保存的部署配置 → 点「一键部署」或「仅打包」。</p>
 *
 * <p>设计取舍：不重复实现一套配置表单，而是复用 {@link MavenDeployRunConfiguration}。
 * ToolWindow 只负责「选哪个模块 + 用哪个配置」，配置细节仍在 Run/Debug Configurations 中编辑，
 * 两处改的是同一份数据，不会出现两边不一致。</p>
 *
 * @author hql on 2026/9/28
 */
public final class QuickDeployToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        QuickDeployPanel panel = new QuickDeployPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * 工具窗主面板。
     */
    static final class QuickDeployPanel extends JPanel implements com.intellij.openapi.Disposable {

        private final Project project;
        private final CollectionListModel<MavenModule> moduleModel = new CollectionListModel<>();
        private final JBList<MavenModule> moduleList = new JBList<>(moduleModel);
        private final JComboBox<MavenDeployRunConfiguration> configCombo = new JComboBox<>();
        private final JButton deployButton = new JButton(DeployBundle.message("btn.deploy"));
        private final JButton packageButton = new JButton(DeployBundle.message("btn.packageOnly"));
        private final JButton stopButton = new JButton(DeployBundle.message("btn.stop"));
        private final JBLabel statusLabel = new JBLabel(" ");

        /** 部署运行期间的按钮状态轮询器，部署结束即停摆并置空 */
        private javax.swing.Timer statusPoller;

        /** 用户已点过停止但流水线还在收尾，用于保留「正在停止」提示 */
        private boolean stopping;

        QuickDeployPanel(@NotNull Project project) {
            super(new BorderLayout());
            this.project = project;
            setBorder(JBUI.Borders.empty(8));
            add(buildHeader(), BorderLayout.NORTH);
            add(buildCenter(), BorderLayout.CENTER);
            add(buildFooter(), BorderLayout.SOUTH);
            refreshModules();
            refreshConfigs();
        }

        @NotNull
        private JComponent buildHeader() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            JBLabel title = new JBLabel("<html><b>" + DeployBundle.message("plugin.toolwindow.title")
                    + "</b></html>");
            title.setBorder(JBUI.Borders.emptyBottom(4));
            panel.add(title);
            panel.add(new JBLabel(DeployBundle.message("plugin.toolwindow.hint")));
            return panel;
        }

        /**
         * 中部：模块列表。
         */
        @NotNull
        private JComponent buildCenter() {
            moduleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            moduleList.setEmptyText(DeployBundle.message("label.noMavenModules"));
            File root = resolveProjectRoot();
            moduleList.setCellRenderer(new ModuleCellRenderer(root == null ? new File(".") : root));
            moduleList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    updateButtons();
                }
            });
            JBScrollPane scrollPane = new JBScrollPane(moduleList);
            scrollPane.setPreferredSize(new java.awt.Dimension(240, 320));
            return scrollPane;
        }

        /**
         * 底部：配置下拉 + 操作按钮 + 状态提示。
         */
        @NotNull
        private JComponent buildFooter() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            JPanel configRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            configRow.add(new JBLabel(DeployBundle.message("label.deployConfig") + ":"));
            configCombo.setMaximumSize(new java.awt.Dimension(220, 28));
            configCombo.addActionListener(e -> updateButtons());
            configRow.add(configCombo);
            panel.add(configRow);

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            deployButton.addActionListener(e -> run(false));
            packageButton.addActionListener(e -> run(true));
            stopButton.addActionListener(e -> stop());
            buttonRow.add(deployButton);
            buttonRow.add(packageButton);
            buttonRow.add(stopButton);
            panel.add(buttonRow);

            statusLabel.setBorder(JBUI.Borders.emptyTop(6));
            panel.add(statusLabel);
            return panel;
        }

        /**
         * 重新扫描 Maven 模块。
         */
        void refreshModules() {
            moduleModel.removeAll();
            File root = resolveProjectRoot();
            if (root != null) {
                for (MavenModule module : new MavenModuleScanner(root).scan()) {
                    moduleModel.add(module);
                }
            }
            if (!moduleModel.isEmpty()) {
                moduleList.setSelectedIndex(0);
            }
            updateButtons();
        }

        /**
         * 重新加载当前项目的部署配置。
         */
        void refreshConfigs() {
            configCombo.removeAllItems();
            configCombo.addItem(null);
            for (MavenDeployRunConfiguration configuration : availableConfigurations()) {
                configCombo.addItem(configuration);
            }
            updateButtons();
        }

        @NotNull
        private List<MavenDeployRunConfiguration> availableConfigurations() {
            java.util.List<MavenDeployRunConfiguration> result = new java.util.ArrayList<>();
            RunManager runManager = RunManager.getInstance(project);
            for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
                if (settings.getConfiguration() instanceof MavenDeployRunConfiguration configuration) {
                    result.add(configuration);
                }
            }
            return result;
        }

        private void updateButtons() {
            boolean hasModule = moduleList.getSelectedValue() != null;
            boolean hasConfig = configCombo.getSelectedItem() != null;
            deployButton.setEnabled(hasModule && hasConfig);
            packageButton.setEnabled(hasModule && hasConfig);
            boolean running = MavenDeployProgramRunner.activeHandler(project) != null;
            stopButton.setEnabled(running);
            if (!hasModule) {
                statusLabel.setText(DeployBundle.message("label.noMavenModules"));
            } else if (!hasConfig) {
                statusLabel.setText(DeployBundle.message("label.noConfigs"));
            } else if (stopping) {
                // 点了停止但流水线还在收尾，此时不能被下面的配置摘要盖掉
                statusLabel.setText(DeployBundle.message("label.stopping"));
            } else {
                MavenDeployRunConfiguration configuration =
                        (MavenDeployRunConfiguration) configCombo.getSelectedItem();
                statusLabel.setText(configuration.describe());
            }
        }

        /**
         * 组装任务并执行。
         *
         * @param packageOnly 仅打包
         */
        private void run(boolean packageOnly) {
            // 活动句柄按项目只存一份：允许并发会让后一个部署覆盖前一个，
            // 「停止」按钮就会停错任务。要部署第二个，先停掉当前这个。
            if (MavenDeployProgramRunner.activeHandler(project) != null) {
                DeployNotifier.notify(project, DeployBundle.message("plugin.name"),
                        DeployBundle.message("label.alreadyRunning"), NotificationType.WARNING);
                return;
            }
            MavenDeployRunConfiguration configuration =
                    (MavenDeployRunConfiguration) configCombo.getSelectedItem();
            MavenModule module = moduleList.getSelectedValue();
            File projectRoot = resolveProjectRoot();
            if (configuration == null || module == null || projectRoot == null) {
                return;
            }
            List<DeployTask> tasks = configuration.toTasks(packageOnly, null);
            // 用 ToolWindow 里选中的模块覆盖配置里的模块路径，实现「换一个模块就发一次」
            for (DeployTask task : tasks) {
                task.setMavenModulePath(module.relativePath(projectRoot));
                task.setDisplayName(configuration.getName() + " / " + module.name());
            }
            MavenDeployProgramRunner.runInRunToolWindow(project, tasks, packageOnly);
            // 启动后立即切到「可停止」态，部署结束再由轮询切回
            updateButtons();
            startStatusPolling();
        }

        /**
         * 请求停止当前部署。
         */
        private void stop() {
            stopActiveDeployment();
            updateButtons();
        }

        private void stopActiveDeployment() {
            if (MavenDeployProgramRunner.stopActive(project)) {
                stopping = true;
                statusLabel.setText(DeployBundle.message("label.stopping"));
            }
        }

        /**
         * 部署期间轮询按钮状态。
         *
         * <p>部署的结束由后台线程触发，没有回调能通知面板，因此用一个低频定时器
         * 轮询句柄状态：只在有部署运行时存在，结束后自行停摆。</p>
         */
        private void startStatusPolling() {
            if (statusPoller != null) {
                return;
            }
            statusPoller = new javax.swing.Timer(400, null);
            statusPoller.setRepeats(true);
            statusPoller.addActionListener(e -> {
                boolean stillRunning = MavenDeployProgramRunner.activeHandler(project) != null;
                if (!stillRunning) {
                    stopping = false;
                    javax.swing.Timer self = statusPoller;
                    statusPoller = null;
                    if (self != null) {
                        self.stop();
                    }
                }
                updateButtons();
            });
            statusPoller.start();
        }

        @Nullable
        private File resolveProjectRoot() {
            String basePath = project.getBasePath();
            if (basePath != null && !basePath.isBlank()) {
                return new File(basePath);
            }
            VirtualFile projectFile = project.getProjectFile();
            if (projectFile != null && projectFile.getParent() != null) {
                return com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(projectFile.getParent());
            }
            VirtualFile guessed = com.intellij.openapi.project.ProjectUtil.guessProjectDir(project);
            return guessed == null ? null : com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(guessed);
        }

        @Override
        public void dispose() {
            if (statusPoller != null) {
                statusPoller.stop();
                statusPoller = null;
            }
        }
    }

    /**
     * 模块列表渲染：显示「名称 相对路径」，同名模块靠路径区分。
     */
    private static final class ModuleCellRenderer
            extends com.intellij.ui.ColoredListCellRenderer<MavenModule> {

        private final File projectRoot;

        ModuleCellRenderer(@NotNull File projectRoot) {
            this.projectRoot = projectRoot;
        }

        @Override
        protected void customizeCellRenderer(@NotNull javax.swing.JList<? extends MavenModule> list,
                                            MavenModule value,
                                            int index,
                                            boolean selected,
                                            boolean hasFocus) {
            if (value == null) {
                return;
            }
            append(value.name() + "  ");
            String relative = value.relativePath(projectRoot);
            if (relative != null && !relative.isEmpty()) {
                append(relative, com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }
    }
}
