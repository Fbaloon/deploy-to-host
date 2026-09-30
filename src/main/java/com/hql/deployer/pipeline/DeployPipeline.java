package com.hql.deployer.pipeline;

import com.hql.deployer.config.PasswordStore;
import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.config.ServerProfileService;

import com.hql.deployer.core.DeployCancellation;
import com.hql.deployer.core.DeployCancelledException;
import com.hql.deployer.core.DeployEvent;
import com.hql.deployer.core.DeployException;
import com.hql.deployer.core.DeployListener;
import com.hql.deployer.core.DeployResult;
import com.hql.deployer.core.DeployStage;
import com.hql.deployer.maven.LocalBuildRunner;
import com.hql.deployer.runconfig.BuildMode;
import com.hql.deployer.ssh.JschSshSession;
import com.hql.deployer.ssh.RemoteCommandResult;
import com.hql.deployer.ssh.SftpUploader;
import com.hql.deployer.ssh.SshSession;
import com.hql.deployer.ssh.SshException;
import com.hql.deployer.util.ExcludeMatcher;
import com.hql.deployer.util.PathUtil;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * 发布流水线。
 *
* <p><b>快速失败契约</b>：六个阶段严格串行，任一阶段失败立即抛
 * {@link DeployException} 并终止，后续阶段不执行。阶段间的补偿动作只有一处——
 * 暂存目录清理，属于「删除半成品」，保证线上目录不残留不完整内容。</p>
 *
 * <p><b>不影响线上服务的保证</b>：全部字节先写入远端暂存目录，激活阶段用单条 {@code mv}
 * 原子切换。中断的最坏情况分两种，都不会让服务中断：</p>
 * <ul>
 *   <li>上传阶段失败 → 线上目录一个字节都没动</li>
 *   <li>部署后命令失败 → 新文件已就位但进程未重启，旧进程仍持有旧文件句柄继续服务</li>
 * </ul>
 *
 * @author hql on 2026/9/28
 */
public final class DeployPipeline {

    private static final Logger LOG = Logger.getInstance(DeployPipeline.class);

    private static final DateTimeFormatter SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SshSession session;
    private final DeployCancellation cancellation = new DeployCancellation();

    /**
     * 使用真实 SSH 连接。
     */
    public DeployPipeline() {
        this(new JschSshSession());
    }

    /**
     * 注入自定义会话，便于测试。
     */
    public DeployPipeline(@NotNull SshSession session) {
        this.session = session;
    }

    /**
     * 请求取消。
     *
     * <p>取消在各阶段的执行循环中被观察：构建子进程被杀、上传在下一个文件前收手、
     * 远端命令通道被断开。已经发出的远端命令不会被强杀在远端留下半完成状态，
     * 但本地通道会立刻断开以结束等待。</p>
     */
    public void cancel() {
        cancellation.cancel();
    }

    public boolean isCancelled() {
        return cancellation.isCancelled();
    }

    /**
     * 取消信号，供流水线内部与 UI 共享。
     */
    @NotNull
    public DeployCancellation getCancellation() {
        return cancellation;
    }

/**
     * 执行一次发布（单台便捷入口）。
     *
     * @param task     任务输入
     * @param listener 事件监听
     * @return 成功时的结果；失败时抛 {@link DeployException}
     */
    @NotNull
    public DeployResult execute(@NotNull DeployTask task, @NotNull DeployListener listener) {
        return executeAll(List.of(task), listener);
    }

    /**
     * 逐台执行发布。
     *
     * <p><b>多台语义</b>：构建只在第一台做一次，随后按传入顺序逐台完成
     * 「连接 → 上传前命令 → 上传 → 生效 → 部署后命令」的完整流程。任一台失败
     * 立即中止后续所有台（沿用快速失败契约），已完成的台不受影响。</p>
     *
     * @param tasks    任务列表（每台一个，通常来自一配置多主机）
     * @param listener 事件监听
     * @return 聚合结果。全部成功时各台上传量累加；某台失败/取消时以该台阶段
     *         与信息为准，上传量为「已成功台 + 失败台已上传部分」之和
     */
    @NotNull
    public DeployResult executeAll(@NotNull List<DeployTask> tasks, @NotNull DeployListener listener) {
        if (tasks.isEmpty()) {
            DeployException empty = new DeployException(DeployStage.PRECHECK, "未选择任何服务器");
            listener.onFailure(empty);
            return DeployResult.failure(DeployStage.PRECHECK, "未选择任何服务器",
                    false, null, 0, 0L, 0L);
        }
        long startTime = System.currentTimeMillis();
        // 全量累加：已成功台 + 当前台已上传部分。单台时退化为该台自身数值。
        int uploadedFiles = 0;
        long uploadedBytes = 0L;
        String backupDirectory = null;
        // 以下状态跟踪「当前台」，用于取消/失败时如实描述线上受影响范围
        boolean connected = false;
        boolean activated = false;
        boolean startedUpload = false;
        // 已完整结束（生效或打包）的台数，用于多台取消/失败消息与 finally 判定
        int finishedHosts = 0;
        String currentHostName = "";

        try {
            // ---------- 阶段 1：预检（所有主机先全部校验，尽早暴露配置问题） ----------
            listener.onStageStart(DeployStage.PRECHECK);
            List<String> targetDirectories = new ArrayList<>();
            for (DeployTask task : tasks) {
                targetDirectories.add(precheck(task, listener));
            }
            cancellation.throwIfCancelled(DeployStage.PRECHECK);

            // ---------- 阶段 2：构建（只一次，产物供所有主机复用） ----------
            DeployTask first = tasks.get(0);
            listener.onStageStart(DeployStage.BUILD);
            runBuild(first, listener);
            File localSource = verifyArtifact(first, listener);
            cancellation.throwIfCancelled(DeployStage.BUILD);


            if (first.isPackageOnly()) {
                listener.onEvent(DeployEvent.success(DeployStage.BUILD, "仅打包模式，构建完成，未执行上传"));
                return DeployResult.success(DeployStage.BUILD, "打包完成",
                        first.isStagingEnabled(), null, 0, 0L, System.currentTimeMillis() - startTime);
            }

            // ---------- 逐台上传并激活 ----------
            for (int index = 0; index < tasks.size(); index++) {
                DeployTask task = tasks.get(index);
                String targetDirectory = targetDirectories.get(index);
                connected = false;
                activated = false;
                startedUpload = false;
                currentHostName = task.getHost().getName();
                if (tasks.size() > 1) {
                    listener.onEvent(DeployEvent.info(DeployStage.PRECHECK,
                            "===== 目标服务器 " + (index + 1) + "/" + tasks.size() + ": "
                                    + currentHostName + " ====="));
                }

                // ---------- 建立连接 ----------
                String password = PasswordStore.getPassword(task.getHost().getId());
                String host = session.connect(task.getHost(), password);
                connected = true;
                listener.onEvent(DeployEvent.success(DeployStage.PRECHECK,
                        "已连接 " + host + " (" + task.getHost().getUsername() + ")"));

                String suffix = LocalDateTime.now().format(SUFFIX_FORMAT);
                String stagingDirectory = task.isStagingEnabled()
                        ? PathUtil.stagingName(targetDirectory, suffix)
                        : targetDirectory;
                String plannedBackup = task.isStagingEnabled()
                        ? PathUtil.backupName(targetDirectory, suffix)
                        : null;

                try (SshSession.SftpChannel sftp = session.openSftp()) {
                    // ---------- 阶段 3：上传前命令（可选） ----------
                    String preCommand = task.getPreUploadCommand().trim();
                    if (!preCommand.isEmpty()) {
                        listener.onStageStart(DeployStage.PRE_COMMAND);
                        runBeforeUploadCommand(task, targetDirectory, preCommand, listener);
                    }

                    // ---------- 阶段 4：上传 ----------
                    startedUpload = true;
                    listener.onStageStart(DeployStage.UPLOAD);
                    if (task.isStagingEnabled()) {
                        listener.onEvent(DeployEvent.info(DeployStage.UPLOAD, "暂存目录: " + stagingDirectory));
                    } else {
                        listener.onEvent(DeployEvent.warn(DeployStage.UPLOAD,
                                "未启用暂存目录，将直接写入 " + targetDirectory
                                        + "，上传中断可能影响线上文件"));
                    }
                    try {
                        SftpUploader.UploadStats stats = SftpUploader.upload(sftp, localSource, stagingDirectory,
                                task.getExcludes(), task.getChmod(),
                                progress -> listener.onEvent(DeployEvent.info(DeployStage.UPLOAD,
                                        "已上传 " + progress.uploadedFiles() + " 个文件: " + progress.currentFile())),
                                cancellation);
                        uploadedFiles += stats.fileCount();
                        uploadedBytes += stats.totalSize();
                        listener.onEvent(DeployEvent.success(DeployStage.UPLOAD,
                                "上传完成: " + stats.fileCount() + " 个文件, " + formatSize(stats.totalSize())));
                        // 上传完成后再查一次：用户可能在上传收尾期间点了停止，
                        // 此时暂存目录完整但尚未激活，仍应清理而不是留给下次部署
                        cancellation.throwIfCancelled(DeployStage.UPLOAD);
                    } catch (RuntimeException e) {
                        // 删除半成品，线上目录未动，无需其它补偿
                        if (task.isStagingEnabled()) {
                            cleanupQuietly(sftp, stagingDirectory, listener);
                        }
                        throw e;
                    }

                    // ---------- 阶段 5：生效 ----------
                    listener.onStageStart(DeployStage.ACTIVATE);
                    backupDirectory = activate(sftp, task, targetDirectory, stagingDirectory, plannedBackup, listener);
                    // 标记必须在 activate 之后：此后线上已是新版本，再取消也不能说「线上未受影响」
                    activated = true;
                }

                // ---------- 阶段 6：部署后命令（可选） ----------
                String afterCommand = task.getAfterDeployCommand().trim();
                if (!afterCommand.isEmpty()) {
                    listener.onStageStart(DeployStage.POST_COMMAND);
                    runAfterCommand(task, targetDirectory, backupDirectory, afterCommand, listener);
                } else {
                    listener.onEvent(DeployEvent.info(DeployStage.POST_COMMAND, "未配置部署后命令，跳过"));
                }

                // 本台完成：释放连接，下一台重新建立（JschSshSession 单连接复用）
                session.close();
                connected = false;
                finishedHosts++;
            }

            String message = tasks.size() == 1 ? "发布完成" : "发布完成（" + tasks.size() + " 台）";
            return DeployResult.success(DeployStage.DONE, message, first.isStagingEnabled(),
                    backupDirectory, uploadedFiles, uploadedBytes, System.currentTimeMillis() - startTime);

        } catch (DeployCancelledException e) {
            // 取消不等于失败，日志与通知都要区分：已激活和未激活的后果完全不同
            String status = activated
                    ? "已取消，但发布内容已生效，线上运行的是本次新版本"
                    : startedUpload
                    ? "已取消，未生效的暂存内容已清理，线上服务未受影响"
                    : "已取消，上传尚未开始，线上服务未受影响";
            if (finishedHosts > 0) {
                status = "已取消。此前 " + finishedHosts + " 台已部署成功，线上运行本次新版本；"
                        + status.replace("已取消，", "当前台" + (connected ? "" : "（未连接）") + " ");
            }
            listener.onEvent(activated
                    ? DeployEvent.warn(e.getStage(), status)
                    : DeployEvent.info(e.getStage(), status));
            listener.onCancelled();
            return DeployResult.cancelled(e.getStage(), e.getMessage(), firstTaskStaging(tasks),
                    backupDirectory, uploadedFiles, uploadedBytes, System.currentTimeMillis() - startTime);
        } catch (DeployException e) {
            listener.onFailure(e);
            String message = describeFailureHost(e.getMessage(), tasks.size(), currentHostName);
            return DeployResult.failure(e.getStage(), message, firstTaskStaging(tasks),
                    backupDirectory, uploadedFiles, uploadedBytes, System.currentTimeMillis() - startTime);
        } catch (SshException e) {
            DeployException wrapped = new DeployException(DeployStage.UPLOAD,
                    "SSH 操作失败: " + e.getMessage(), e);
            listener.onFailure(wrapped);
            String message = describeFailureHost(wrapped.getMessage(), tasks.size(), currentHostName);
            return DeployResult.failure(wrapped.getStage(), message, firstTaskStaging(tasks),
                    backupDirectory, uploadedFiles, uploadedBytes, System.currentTimeMillis() - startTime);
        } finally {
            if (connected) {
                session.close();
            }
        }
    }

    /**
     * 多台失败时标注失败发生的服务器；单台保持原始信息不变。
     */
    @NotNull
    private static String describeFailureHost(@NotNull String message,
                                              int hostCount,
                                              @NotNull String currentHostName) {
        if (hostCount <= 1 || currentHostName.isBlank()) {
            return message;
        }
        return "服务器 " + currentHostName + " 失败: " + message;
    }

    private static boolean firstTaskStaging(@NotNull List<DeployTask> tasks) {
        return !tasks.isEmpty() && tasks.get(0).isStagingEnabled();
    }

    // ------------------------------------------------------------------ 阶段 1：预检

    /**
     * 校验配置与本地环境，解析出最终的远端目标目录。任何不满足都直接失败，不做任何远端改动。
     */
    @NotNull
    private String precheck(@NotNull DeployTask task, @NotNull DeployListener listener) {
        ServerProfile host = task.getHost();
        if (!task.isPackageOnly()) {
            // 仅打包不碰远端，因此不要求服务器与远端目录配置
            String validationError = host.validate();
            if (validationError != null) {
                throw new DeployException(DeployStage.PRECHECK, "服务器配置不完整: " + validationError);
            }
            if (task.getTargetDirectory().trim().isEmpty()) {
                throw new DeployException(DeployStage.PRECHECK, "目标目录不能为空");
            }
        }
        if (task.getBuildMode() == BuildMode.CUSTOM_COMMAND && !task.isPackageOnly()
                && task.getCustomCommand().isBlank()) {
            throw new DeployException(DeployStage.PRECHECK, "自定义命令模式未填写构建命令");
        }
        if (task.getBuildMode() == BuildMode.MAVEN_BUILD && !task.isPackageOnly()
                && task.getMavenGoals().isBlank()) {
            throw new DeployException(DeployStage.PRECHECK, "Maven 模式未填写构建 goals");
        }

        // 提前探测本地构建命令，避免「构建跑一半才发现 mvn 不在 PATH」
        if (task.requiresBuild()) {
            String executable = buildExecutable(task);
            if (executable == null) {
                throw new DeployException(DeployStage.PRECHECK,
                        "未找到构建命令 " + firstTokenOf(task) + "，请检查 PATH 或在设置中指定完整路径");
            }
            listener.onEvent(DeployEvent.info(DeployStage.PRECHECK, "构建命令: " + executable));
        }

        if (task.isPackageOnly()) {
            // 仅打包不会用到远端目录，提前返回，避免空配置卡住本地打包
            return "";
        }
        String targetDirectory = PathUtil.joinRemote(host.getRemoteBaseDir(), task.getTargetDirectory());

        if (targetDirectory.isEmpty()) {
            throw new DeployException(DeployStage.PRECHECK, "目标目录解析后为空");
        }
        listener.onEvent(DeployEvent.info(DeployStage.PRECHECK,
                "目标目录: " + targetDirectory + (task.isStagingEnabled() ? "（启用暂存）" : "（直接写入）")));
        return targetDirectory;
    }

    // ------------------------------------------------------------------ 阶段 2：构建

    /**
     * 执行构建命令。{@code UPLOAD_FILE} 模式直接跳过。
     */
    private void runBuild(@NotNull DeployTask task, @NotNull DeployListener listener) {
        if (!task.requiresBuild()) {
            listener.onEvent(DeployEvent.info(DeployStage.BUILD, "部署内容为「上传文件/目录」，跳过构建"));
            return;
        }

        List<String> command = LocalBuildRunner.prepareCommandForExecution(buildCommandTokens(task));
        File workingDir = buildWorkingDir(task);

        listener.onEvent(DeployEvent.command(DeployStage.BUILD,
                "工作目录: " + workingDir.getAbsolutePath()));
        listener.onEvent(DeployEvent.command(DeployStage.BUILD, String.join(" ", command)));

        LocalBuildRunner.Result result;
        try {
            result = LocalBuildRunner.run(command, workingDir, task.getPackageTimeoutMs(),
                    buildEnvironment(task, listener),
                    text -> listener.onEvent(DeployEvent.info(DeployStage.BUILD, text.stripTrailing())),
                    cancellation);
        } catch (com.intellij.execution.ExecutionException e) {
            throw new DeployException(DeployStage.BUILD, "构建命令启动失败: " + e.getMessage(), e);
        }

        // 取消要单独分流：不是失败，也不需要打印构建尾部日志，用户只是不想等了
        if (result.cancelled()) {
            throw new DeployCancelledException(DeployStage.BUILD, "构建已取消，构建进程已终止");
        }

        if (!result.isSuccess()) {
            listener.onEvent(DeployEvent.error(DeployStage.BUILD, result.describeFailure()));
            String tail = tail(result.stdout() + "\n" + result.stderr(), 20);
            if (!tail.isBlank()) {
                listener.onEvent(DeployEvent.info(DeployStage.BUILD, "---- 构建输出（末 20 行）----"));
                for (String line : tail.split("\r?\n")) {
                    listener.onEvent(DeployEvent.info(DeployStage.BUILD, line));
                }
            }
            throw new DeployException(DeployStage.BUILD, result.describeFailure());
        }
        listener.onEvent(DeployEvent.success(DeployStage.BUILD, "构建成功"));
    }

    /**
     * 校验待上传产物存在且非空。三种模式都会执行，避免传空目录或错路径。
     */
    @NotNull
    private File verifyArtifact(@NotNull DeployTask task, @NotNull DeployListener listener) {
        File localSource = task.resolveLocalSource();
        if (!localSource.exists()) {
            throw new DeployException(DeployStage.BUILD,
                    "待上传路径不存在: " + localSource.getAbsolutePath()
                            + "（配置值: " + task.getLocalPath() + "）");
        }
        if (localSource.isDirectory()) {
            PathUtil.FileStats stats = PathUtil.collectStats(localSource, task.getExcludes());
            if (stats.fileCount() == 0) {
                throw new DeployException(DeployStage.BUILD,
                        "待上传目录为空: " + localSource.getAbsolutePath()
                                + "（排除规则: " + ExcludeMatcher.joinRules(task.getExcludes()) + "）");
            }
            listener.onEvent(DeployEvent.info(DeployStage.BUILD,
                    "待上传: " + stats.fileCount() + " 个文件, " + formatSize(stats.totalBytes())
                            + " ← " + localSource.getAbsolutePath()));
        } else {
            listener.onEvent(DeployEvent.info(DeployStage.BUILD,
                    "待上传: 1 个文件, " + formatSize(localSource.length())
                            + " ← " + localSource.getAbsolutePath()));
        }
        return localSource;
    }

    // ------------------------------------------------------------------ 阶段 5：生效

    /**
     * 原子切换：先把线上目录改名备份，再把暂存目录改名到线上目录。
     *
     * @return 备份目录路径；未产生备份时返回 {@code null}
     */
    @Nullable
    private String activate(@NotNull SshSession.SftpChannel sftp,
                            @NotNull DeployTask task,
                            @NotNull String targetDirectory,
                            @NotNull String stagingDirectory,
                            @Nullable String plannedBackup,
                            @NotNull DeployListener listener) {
        if (!task.isStagingEnabled()) {
            listener.onEvent(DeployEvent.success(DeployStage.ACTIVATE, "直接写入模式，跳过切换"));
            return null;
        }

        boolean backupCreated = false;
        boolean targetExisted = sftp.exists(targetDirectory);
        if (targetExisted) {
            sftp.rename(targetDirectory, plannedBackup);
            backupCreated = true;
            listener.onEvent(DeployEvent.info(DeployStage.ACTIVATE, "已备份原目录: " + plannedBackup));
        }
        try {
            sftp.rename(stagingDirectory, targetDirectory);
        } catch (RuntimeException e) {
            // 补偿：把备份改回原名，保证线上目录可用
            if (backupCreated) {
                try {
                    sftp.rename(plannedBackup, targetDirectory);
                    listener.onEvent(DeployEvent.info(DeployStage.ACTIVATE, "切换失败，已还原原目录"));
                } catch (RuntimeException rollbackError) {
                    listener.onEvent(DeployEvent.error(DeployStage.ACTIVATE,
                            "还原失败，请手动检查目录: " + targetDirectory + " / " + plannedBackup));
                    LOG.warn("激活失败且还原失败", rollbackError);
                }
            }
            throw new DeployException(DeployStage.ACTIVATE, "切换到目标目录失败: " + e.getMessage(), e);
        }
        listener.onEvent(DeployEvent.success(DeployStage.ACTIVATE, "已生效: " + targetDirectory));
        pruneOldBackups(sftp, task, targetDirectory, listener);
        return backupCreated ? plannedBackup : null;
    }

    /**
     * 清理超出保留份数的历史备份。
     *
     * <p>时机定在「切换成功」而非「整个部署成功」：此刻新版本已经上线，历史备份只剩回滚价值；
     * 而部署后命令可能长时间挂起或失败，不该把清理拖到它后面。被删的永远只是比
     * 「上一个版本」更老的历史——本次刚生成的备份必定排在最新一位、从不被删，
     * 因此回退一步的路径任何时候都存在。</p>
     *
     * <p>反过来，切换失败时一个备份都不会动：那正是用户要拿来回滚的东西。</p>
     *
     * <p>任何失败都只告警不抛出：内容已经生效，不能因为清理旧备份而把这次部署判成失败。</p>
     */
    private void pruneOldBackups(@NotNull SshSession.SftpChannel sftp,
                                 @NotNull DeployTask task,
                                 @NotNull String targetDirectory,
                                 @NotNull DeployListener listener) {
        int keep = task.getHost().getBackupKeepCount();
        if (keep <= 0) {
            return;
        }
        try {
            String parent = PathUtil.remoteParent(targetDirectory);
            List<String> expired = PathUtil.selectExpiredBackups(
                    sftp.listDirectory(parent), targetDirectory, keep);
            if (expired.isEmpty()) {
                return;
            }
            int removed = 0;
            for (String name : expired) {
                try {
                    sftp.removeAll(parent + "/" + name);
                    removed++;
                } catch (RuntimeException e) {
                    listener.onEvent(DeployEvent.warn(DeployStage.ACTIVATE,
                            "清理历史备份失败，保留 " + name + ": " + e.getMessage()));
                    LOG.warn("清理历史备份失败 " + name, e);
                }
            }
            listener.onEvent(DeployEvent.info(DeployStage.ACTIVATE,
                    "已清理 " + removed + " 个历史备份，保留最近 " + keep + " 份"));
        } catch (RuntimeException e) {
            // 列举父目录失败通常是没权限或目录结构异常，此时保持现状即可
            listener.onEvent(DeployEvent.warn(DeployStage.ACTIVATE,
                    "列举历史备份失败，跳过清理: " + e.getMessage()));
            LOG.warn("列举历史备份失败", e);
        }
    }

    // ------------------------------------------------------------------ 阶段 3：上传前命令

    /**
     * 执行上传前命令。
     *
     * <p>语义与部署后命令相同：命令失败立即终止流水线，且此时尚未上传任何字节，
     * 线上目录完全未动，无需任何补偿。</p>
     */
    private void runBeforeUploadCommand(@NotNull DeployTask task,
                                        @NotNull String targetDirectory,
                                        @NotNull String command,
                                        @NotNull DeployListener listener) {
        listener.onEvent(DeployEvent.command(DeployStage.PRE_COMMAND, command));
        RemoteCommandResult result;
        try {
            result = session.exec(command, targetDirectory, task.getHost().getExecTimeoutMs(),
                    text -> listener.onEvent(DeployEvent.info(DeployStage.PRE_COMMAND, text)), cancellation);
        } catch (DeployCancelledException e) {
            // exec 取消时统一标记为部署后命令阶段，上传前命令需纠正回来
            throw new DeployCancelledException(DeployStage.PRE_COMMAND,
                    e.getMessage() == null ? "上传前命令已取消" : e.getMessage());
        }

        streamRemoteOutput(result, DeployStage.PRE_COMMAND, listener);
        if (!result.isSuccess()) {
            String message = "上传前命令退出码 " + result.exitCode() + "，已终止部署，线上目录未受影响";
            String diagnosis = diagnoseExitCode(result.exitCode());
            if (diagnosis != null) {
                message = message + "。" + diagnosis;
            }
            throw new DeployException(DeployStage.PRE_COMMAND, message);
        }
        listener.onEvent(DeployEvent.success(DeployStage.PRE_COMMAND, "上传前命令执行成功"));
    }

    // ------------------------------------------------------------------ 阶段 6：部署后命令

    private void runAfterCommand(@NotNull DeployTask task,
                                 @NotNull String targetDirectory,
                                 @Nullable String backupDirectory,
                                 @NotNull String command,
                                 @NotNull DeployListener listener) {
listener.onEvent(DeployEvent.command(DeployStage.POST_COMMAND, command));
        RemoteCommandResult result = session.exec(command, targetDirectory, task.getHost().getExecTimeoutMs(),
                text -> listener.onEvent(DeployEvent.info(DeployStage.POST_COMMAND, text)), cancellation);

        streamRemoteOutput(result, DeployStage.POST_COMMAND, listener);
        if (!result.isSuccess()) {
            String message = "部署后命令退出码 " + result.exitCode() + "，新文件已就位但服务未重启";
            String diagnosis = diagnoseExitCode(result.exitCode());
            if (diagnosis != null) {
                message = message + "。" + diagnosis;
            }
            if (backupDirectory != null) {
                listener.onEvent(DeployEvent.warn(DeployStage.POST_COMMAND,
                        "如需回滚，请将备份目录 " + backupDirectory + " 改回目标目录 " + targetDirectory));
            }
            throw new DeployException(DeployStage.POST_COMMAND, message);
        }
        listener.onEvent(DeployEvent.success(DeployStage.POST_COMMAND, "部署后命令执行成功"));
    }

    /**
     * 把远端命令的标准输出与错误输出转成监听器事件。
     *
     * <p>stdout 与 stderr 都按行拆分：命令输出可能夹杂时间戳或控制字符，按行渲染
     * 既便于日志阅读，也避免单条超长行把控制台撑爆。</p>
     */
    private static void streamRemoteOutput(@NotNull RemoteCommandResult result,
                                           @NotNull DeployStage stage,
                                           @NotNull DeployListener listener) {
        if (!result.stdout().isBlank()) {
            for (String line : result.stdout().split("\r?\n")) {
                if (!line.isBlank()) {
                    listener.onEvent(DeployEvent.info(stage, line));
                }
            }
        }
        if (!result.isSuccess() && !result.stderr().isBlank()) {
            for (String line : result.stderr().split("\r?\n")) {
                if (!line.isBlank()) {
                    listener.onEvent(DeployEvent.error(stage, line));
                }
            }
        }
    }

    /**
     * 把 POSIX 约定的「命令找不到 / 不可执行」退出码翻译成可操作的排查指引。
     *
     * <p>远端命令跑在非交互、非登录 shell 里，不加载 {@code .bashrc} 的别名、函数和自定义 PATH，
     * 因此 127 绝大多数不是服务本身的问题，而是命令写法或脚本本身的问题。</p>
     *
     * @return 排查提示；无特殊含义时返回 {@code null}
     */
    @Nullable
    static String diagnoseExitCode(int exitCode) {
        return switch (exitCode) {
            case 127 -> "远端 shell 找不到该命令。该命令在非交互式 shell 中执行，不会加载 .bashrc 的别名、"
                    + "函数和自定义 PATH，请改用命令或脚本的绝对路径；另外请确认脚本首行 shebang 没有混入"
                    + "Windows 换行符（CRLF 会让内核报「找不到文件」而非「格式错误」），"
                    + "以及脚本是否需要额外参数";
            case 126 -> "远端 shell 找到了该命令但无法执行，通常是脚本缺少可执行权限，"
                    + "请在服务器上执行 chmod +x <脚本路径>";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 清理暂存目录。失败只告警不抛错——此处已在失败路径上，不能用第二个异常掩盖首个失败。
     */
    private void cleanupQuietly(@NotNull SshSession.SftpChannel sftp,
                                @NotNull String stagingDirectory,
                                @NotNull DeployListener listener) {
        try {
            sftp.removeAll(stagingDirectory);
            listener.onEvent(DeployEvent.info(DeployStage.UPLOAD, "已清理暂存目录: " + stagingDirectory));
        } catch (RuntimeException e) {
            listener.onEvent(DeployEvent.warn(DeployStage.UPLOAD,
                    "暂存目录清理失败，请手动删除: " + stagingDirectory + " (" + e.getMessage() + ")"));
        }
    }

    /**
     * 解析构建命令可执行文件路径，用于预检。
     */
    @Nullable
    private static String buildExecutable(@NotNull DeployTask task) {
        if (task.getBuildMode() == BuildMode.MAVEN_BUILD) {
            String configured = ServerProfileService.getInstance().toDefaults().mavenExecutable;
            return LocalBuildRunner.resolveMavenExecutable(configured);
        }
        return LocalBuildRunner.resolveExecutable(firstTokenOf(task));
    }


    @NotNull
    private static String firstTokenOf(@NotNull DeployTask task) {
        if (task.getBuildMode() == BuildMode.MAVEN_BUILD) {
            String configured = ServerProfileService.getInstance().toDefaults().mavenExecutable;
            return configured == null || configured.isBlank() ? "mvn" : configured.trim();
        }
        List<String> tokens = LocalBuildRunner.tokenize(task.getCustomCommand());
        return tokens.isEmpty() ? "" : tokens.get(0);
    }


    @NotNull
    private static List<String> buildCommandTokens(@NotNull DeployTask task) {
        if (task.getBuildMode() == BuildMode.MAVEN_BUILD) {
            return mavenCommandTokens(task);
        }
        List<String> tokens = LocalBuildRunner.tokenize(task.getCustomCommand());
        if (tokens.isEmpty()) {
            throw new DeployException(DeployStage.BUILD, "自定义构建命令为空");
        }
        return tokens;
    }

    /**
     * 组装 Maven 命令：{@code mvn -f <pom> <goals> [-DskipTests]}。
     * 工作目录已设为模块目录，因此省略 {@code -f} 之外的多余参数。
     */
    @NotNull
    private static List<String> mavenCommandTokens(@NotNull DeployTask task) {
        List<String> tokens = new ArrayList<>();
        String configured = ServerProfileService.getInstance().toDefaults().mavenExecutable;
        String resolved = LocalBuildRunner.resolveMavenExecutable(configured);
        tokens.add(resolved != null ? resolved : "mvn");
        File moduleDir = buildWorkingDir(task);
        File pom = new File(moduleDir, "pom.xml");
        if (pom.isFile()) {
            tokens.add("-f");
            tokens.add(pom.getAbsolutePath());
        }
        for (String goal : task.getMavenGoals().trim().split("\\s+")) {
            if (!goal.isEmpty()) {
                tokens.add(goal);
            }
        }
        if (task.isSkipTests()) {
            tokens.add("-DskipTests=true");
        }
        return tokens;
    }


    @NotNull
    private static File buildWorkingDir(@NotNull DeployTask task) {
        String relative = task.getBuildMode() == BuildMode.MAVEN_BUILD
                ? task.getMavenModulePath()
                : task.getCustomWorkingDir();
        File dir = PathUtil.resolve(task.getProjectRoot(), relative);
        if (!dir.isDirectory()) {
            throw new DeployException(DeployStage.BUILD, "构建工作目录不存在: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 组装构建子进程的环境变量。
     *
     * <p>核心是 {@code JAVA_HOME}：Maven 启动脚本据此挑选 JDK。IDE 进程继承的是
     * 系统 {@code JAVA_HOME}，在多 JDK 机器上常与项目 {@code pom.xml} 要求的
     * {@code <java.version>} 不一致，导致「无效的目标发行版: N」。这里用项目 SDK
     * 覆盖，让构建 JDK 与 IDEA 中看到的一致。</p>
     *
     * @return 需覆盖的环境变量；无需覆盖时返回 {@code null}
     */
    @Nullable
    private static Map<String, String> buildEnvironment(@NotNull DeployTask task,
                                                        @NotNull DeployListener listener) {
        String jdkHome = task.getBuildJdkPath();
        if (jdkHome == null) {
            return null;
        }
        if (jdkHome.equals(System.getenv("JAVA_HOME"))) {
            return null;
        }
        listener.onEvent(DeployEvent.info(DeployStage.BUILD, "构建 JDK: " + jdkHome));
        return Map.of("JAVA_HOME", jdkHome);
    }


    @NotNull
    private static String tail(@NotNull String text, int maxLines) {
        String[] lines = text.split("\r?\n");
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            builder.append(lines[i]).append('\n');
        }
        return builder.toString();
    }

    @NotNull
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
