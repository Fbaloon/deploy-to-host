package com.hql.deployer.runconfig;

import com.hql.deployer.config.ServerProfileService;
import com.hql.deployer.core.DeployEvent;
import com.hql.deployer.core.DeployException;
import com.hql.deployer.core.DeployListener;
import com.hql.deployer.core.DeployResult;
import com.hql.deployer.core.DeployStage;
import com.hql.deployer.maven.BuildJdkResolver;
import com.hql.deployer.pipeline.DeployPipeline;
import com.hql.deployer.pipeline.DeployTask;
import com.hql.deployer.util.DeployNotifier;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.BorderLayout;
import java.util.List;

/**
 * 把「部署到服务器」配置接到 IDE 的运行框架上。
 *
 * <p>不启动真实进程，而是构造一个受控的 {@link ProcessHandler} 与自实现的
 * {@link ExecutionConsole}，挂到标准 Run 工具窗。这样能直接获得日志面板、停止按钮、
 * 运行历史与「重新运行」等能力，无需自绘对话框。</p>
 *
 * @author hql on 2026/9/28
 */
public final class MavenDeployProgramRunner implements ProgramRunner<RunnerSettings> {

    public static final String RUNNER_ID = "MavenQuickDeployRunner";

    private static final Logger LOG = Logger.getInstance(MavenDeployProgramRunner.class);

    /**
     * Run 面板中「仅打包」按钮通过 user data 传递的标记。
     */
    public static final Key<Boolean> PACKAGE_ONLY =
            Key.create("mavenQuickDeploy.packageOnly");

    /**
     * 当前项目正在运行的部署句柄。ToolWindow 的「停止」按钮据此反查，
     * 免去让调用方各自保存一份引用。
     */
    private static final Key<DeployProcessHandler> ACTIVE_HANDLER =
            Key.create("mavenQuickDeploy.activeHandler");

    /**
     * 取得项目当前正在运行的部署句柄，无运行中的部署时返回 {@code null}。
     */
    @Nullable
    public static DeployProcessHandler activeHandler(@NotNull Project project) {
        DeployProcessHandler handler = project.getUserData(ACTIVE_HANDLER);
        return handler != null && !handler.isProcessTerminated() ? handler : null;
    }

    /**
     * 请求停止项目当前正在运行的部署。
     *
     * @return 是否确实触发了停止；没有运行中的部署时返回 {@code false}
     */
    public static boolean stopActive(@NotNull Project project) {
        DeployProcessHandler handler = activeHandler(project);
        if (handler == null) {
            return false;
        }
        handler.destroyProcess();
        return true;
    }

    @Override
    @NotNull
    public String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @Nullable RunProfile profile) {
        return DefaultRunExecutor.EXECUTOR_ID.equals(executorId)
                && profile instanceof MavenDeployRunConfiguration;
    }

    @Override
    public String getStartActionText(@NotNull com.intellij.execution.Executor executor,
                                     @NotNull com.intellij.execution.configurations.RunConfiguration runConfiguration) {
        return "部署到服务器";
    }

    @Override
    public void execute(@NotNull ExecutionEnvironment environment) throws ExecutionException {
        MavenDeployRunConfiguration configuration =
                (MavenDeployRunConfiguration) environment.getRunProfile();
        boolean packageOnly = Boolean.TRUE.equals(environment.getUserData(PACKAGE_ONLY));

        List<DeployTask> tasks = configuration.toTasks(packageOnly, null);
        if (tasks.isEmpty()) {
            throw new ExecutionException(configuration.describe() + " 未选择任何服务器");
        }
        String displayName = tasks.size() == 1 ? tasks.get(0).getDisplayName()
                : tasks.get(0).getDisplayName() + "（" + tasks.size() + " 台）";
        DeployLogConsole console = new DeployLogConsole(displayName);
        DeployProcessHandler handler = new DeployProcessHandler();

        RunContentDescriptor descriptor =
                new RunContentDescriptor(console, handler, console.getComponent(), displayName, null);

        startDeployment(environment.getProject(), tasks, console, handler, packageOnly);

        RunContentManager.getInstance(environment.getProject())
                .showRunContent(environment.getExecutor(), descriptor);
    }

    /**
     * 启动部署。
     *
     * @param project     项目
     * @param tasks       部署任务列表（多台时按序执行，首台失败即中止后续）
     * @param console     日志控制台
     * @param handler     进程句柄（供「停止」按钮取消，必须与内部流水线同源）
     * @param packageOnly 仅打包
     */
    public static void startDeployment(@NotNull Project project,
                                       @NotNull List<DeployTask> tasks,
                                       @NotNull DeployLogConsole console,
                                       @NotNull DeployProcessHandler handler,
                                       boolean packageOnly) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        DeployListener listener = console.asListener();
        handler.startNotify();
        project.putUserData(ACTIVE_HANDLER, handler);
        // 部署结束（含取消）后清空，ToolWindow 的「停止」按钮据此回到禁用态。
        // 直接实现 ProcessListener 而不用已废弃的 ProcessAdapter
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                project.putUserData(ACTIVE_HANDLER, null);
            }
        });

        // 从项目 SDK 解析构建 JDK，避免继承到版本过低的系统 JAVA_HOME。
        // 多台共享同一份构建，只需解析一次。
        DeployTask first = tasks.get(0);
        if (first.getBuildJdkPath() == null) {
            first.setBuildJdkPath(BuildJdkResolver.resolve(project));
        }

        Thread worker = new Thread(() -> {
            DeployResult result;
            try {
                result = handler.getPipeline().executeAll(tasks, listener);
            } catch (RuntimeException e) {
                DeployException failure = e instanceof DeployException deployException
                        ? deployException
                        : new DeployException(DeployStage.PRECHECK, "部署异常终止: " + e.getMessage(), e);
                listener.onFailure(failure);
                result = DeployResult.failure(failure.getStage(), failure.getMessage(),
                        first.isStagingEnabled(), null, 0, 0L, 0L);
            }

            boolean success = result.success();
            // 取消是用户自己的选择，不该弹出「部署失败」的红色通知
            if (!result.cancelled()) {
                if (packageOnly) {
                    DeployNotifier.notifyPackaged(project, first.getDisplayName(), result);
                } else {
                    DeployNotifier.notifyDeployed(project, first.getDisplayName(), result);
                }
            }
            handler.finish(success);
        }, "MavenQuickDeploy-" + first.getDisplayName());
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 供 ToolWindow 使用：跑一次部署并把控制台挂到 Run 工具窗。
     */
    public static void runInRunToolWindow(@NotNull Project project,
                                          @NotNull List<DeployTask> tasks,
                                          boolean packageOnly) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        String displayName = tasks.size() == 1 ? tasks.get(0).getDisplayName()
                : toolsDisplayName(tasks);
        DeployLogConsole console = new DeployLogConsole(displayName);
        DeployProcessHandler handler = new DeployProcessHandler();
        RunContentDescriptor descriptor =
                new RunContentDescriptor(console, handler, console.getComponent(), displayName, null);

        startDeployment(project, tasks, console, handler, packageOnly);

        RunContentManager.getInstance(project)
                .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
    }

    /**
     * ToolWindow 多台部署的显示名：在配置名尾部追加「（N 台）」。
     */
    @NotNull
    private static String toolsDisplayName(@NotNull List<DeployTask> tasks) {
        String base = tasks.get(0).getDisplayName();
        return base + "（" + tasks.size() + " 台）";
    }

    /**
     * 部署的进程句柄。{@code destroyProcess()} 即 Run 面板的「停止」按钮。
     *
     * <p>停止只置位取消标记，由构建子进程、上传遍历、远端命令各自的中断点收手；
     * 已经发出的远端命令不强杀，避免远端留下半完成状态。流水线结束后
     * {@link #finish(boolean)} 才真正终结句柄，Run 面板的停止按钮因此不会「按了没反应」。</p>
     */
    public static final class DeployProcessHandler extends ProcessHandler {

        private final DeployPipeline pipeline = new DeployPipeline();

        /**
         * 与「停止」按钮共享的流水线实例，保证取消信号能真正到达执行逻辑。
         */
        @NotNull
        public DeployPipeline getPipeline() {
            return pipeline;
        }

        @Override
        public void destroyProcessImpl() {
            // 重复点击「停止」不应刷屏，只在第一次真正置位时生效；
            // 这里不发 notifyProcessTerminated——流水线真正收尾时才终结句柄，
            // 否则 Run 面板会以为任务已结束，而取消其实还没生效
            pipeline.cancel();
        }

        @Override
        protected void detachProcessImpl() {
            notifyProcessDetached();
        }

        @Override
        public boolean detachIsDefault() {
            return false;
        }

        @Override
        public @NotNull java.io.OutputStream getProcessInput() {
            return java.io.OutputStream.nullOutputStream();
        }

        void finish(boolean success) {
            notifyProcessTerminated(success ? 0 : 1);
        }
    }
}
