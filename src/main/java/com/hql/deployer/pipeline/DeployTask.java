package com.hql.deployer.pipeline;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.runconfig.BuildMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * 一次发布任务的全部输入。由 Run Configuration 或 ToolWindow 组装后交给流水线执行。
 *
 * @author hql on 2026/9/28
 */
public final class DeployTask {

    /** 项目根目录 */
    private final File projectRoot;

    /** 部署内容类型：Maven 构建 / 直接上传 / 自定义命令 */
    private BuildMode buildMode = BuildMode.MAVEN_BUILD;

    /** MAVEN_BUILD 模式：模块目录相对项目根的路径，空串表示项目根自身 */
    private String mavenModulePath = "";

    /** MAVEN_BUILD 模式：执行的 goals，如 {@code clean package} */
    private String mavenGoals = "clean package";

    /** MAVEN_BUILD 模式：是否跳过测试 */
    private boolean skipTests = true;

    /** CUSTOM 模式：自定义构建命令 */
    private String customCommand = "";

    /** CUSTOM 模式：工作目录相对项目根，空串表示项目根 */
    private String customWorkingDir = "";

    /** 待上传的本地源，相对项目根或绝对路径 */
    private String localPath = "target";

    /** 上传排除规则 */
    private List<String> excludes = List.of();

    /** 远端权限，如 {@code 0644}，可空 */
    private String chmod;

    /** 目标服务器 */
    private ServerProfile host;

    /** 目标目录（相对服务器配置的远端根目录，或绝对路径） */
    private String targetDirectory = "";

    /** 上传前在远端执行的命令，可空 */
    private String preUploadCommand = "";

    /** 上传完成后执行的远端命令，可空 */
    private String afterDeployCommand = "";

    /** 是否启用远端暂存目录 */
    private boolean stagingEnabled = true;

    /** 构建超时（毫秒） */
    private int packageTimeoutMs = 600_000;

    /** 任务显示名，用于日志与通知 */
    private String displayName = "部署";

    /** 是否只执行构建、不上传（对应「仅打包」按钮） */
    private boolean packageOnly;

    /**
     * 构建用 JDK 根目录（即子进程的 {@code JAVA_HOME}）。
     *
     * <p>{@code null} 表示沿用当前进程环境变量。由 {@code BuildJdkResolver}
     * 从 IDEA 项目 SDK 解析后填入，避免本机 {@code JAVA_HOME} 指向的 JDK
     * 版本低于 {@code pom.xml} 要求时编译失败。</p>
     */
    private String buildJdkPath;

    public DeployTask(@NotNull File projectRoot, @NotNull ServerProfile host) {
        this.projectRoot = projectRoot;
        this.host = host;
    }

    @NotNull
    public File getProjectRoot() {
        return projectRoot;
    }

    @NotNull
    public BuildMode getBuildMode() {
        return buildMode;
    }

    @NotNull
    public DeployTask setBuildMode(@NotNull BuildMode buildMode) {
        this.buildMode = buildMode;
        return this;
    }

    @NotNull
    public String getMavenModulePath() {
        return mavenModulePath;
    }

    @NotNull
    public DeployTask setMavenModulePath(@NotNull String mavenModulePath) {
        this.mavenModulePath = mavenModulePath;
        return this;
    }

    @NotNull
    public String getMavenGoals() {
        return mavenGoals;
    }

    @NotNull
    public DeployTask setMavenGoals(@NotNull String mavenGoals) {
        this.mavenGoals = mavenGoals;
        return this;
    }

    public boolean isSkipTests() {
        return skipTests;
    }

    @NotNull
    public DeployTask setSkipTests(boolean skipTests) {
        this.skipTests = skipTests;
        return this;
    }

    @NotNull
    public String getCustomCommand() {
        return customCommand;
    }

    @NotNull
    public DeployTask setCustomCommand(@NotNull String customCommand) {
        this.customCommand = customCommand;
        return this;
    }

    @NotNull
    public String getCustomWorkingDir() {
        return customWorkingDir;
    }

    @NotNull
    public DeployTask setCustomWorkingDir(@NotNull String customWorkingDir) {
        this.customWorkingDir = customWorkingDir;
        return this;
    }

    @NotNull
    public String getLocalPath() {
        return localPath;
    }

    @NotNull
    public DeployTask setLocalPath(@NotNull String localPath) {
        this.localPath = localPath;
        return this;
    }

    @NotNull
    public List<String> getExcludes() {
        return excludes;
    }

    @NotNull
    public DeployTask setExcludes(@Nullable List<String> excludes) {
        this.excludes = excludes == null ? List.of() : List.copyOf(excludes);
        return this;
    }

    @Nullable
    public String getChmod() {
        return chmod;
    }

    @NotNull
    public DeployTask setChmod(@Nullable String chmod) {
        this.chmod = chmod == null || chmod.isBlank() ? null : chmod.trim();
        return this;
    }

    @NotNull
    public ServerProfile getHost() {
        return host;
    }

    @NotNull
    public DeployTask setHost(@NotNull ServerProfile host) {
        this.host = host;
        return this;
    }

    @NotNull
    public String getTargetDirectory() {
        return targetDirectory;
    }

    @NotNull
    public DeployTask setTargetDirectory(@NotNull String targetDirectory) {
        this.targetDirectory = targetDirectory;
        return this;
    }

    @NotNull
    public String getPreUploadCommand() {
        return preUploadCommand;
    }

    @NotNull
    public DeployTask setPreUploadCommand(@NotNull String preUploadCommand) {
        this.preUploadCommand = preUploadCommand == null ? "" : preUploadCommand.trim();
        return this;
    }

    @NotNull
    public String getAfterDeployCommand() {
        return afterDeployCommand;
    }

    @NotNull
    public DeployTask setAfterDeployCommand(@NotNull String afterDeployCommand) {
        this.afterDeployCommand = afterDeployCommand;
        return this;
    }

    public boolean isStagingEnabled() {
        return stagingEnabled;
    }

    @NotNull
    public DeployTask setStagingEnabled(boolean stagingEnabled) {
        this.stagingEnabled = stagingEnabled;
        return this;
    }

    public int getPackageTimeoutMs() {
        return packageTimeoutMs;
    }

    @NotNull
    public DeployTask setPackageTimeoutMs(int packageTimeoutMs) {
        this.packageTimeoutMs = packageTimeoutMs;
        return this;
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    @NotNull
    public DeployTask setDisplayName(@NotNull String displayName) {
        this.displayName = displayName;
        return this;
    }

    public boolean isPackageOnly() {
        return packageOnly;
    }

    @NotNull
    public DeployTask setPackageOnly(boolean packageOnly) {
        this.packageOnly = packageOnly;
        return this;
    }

    /**
     * 构建用 JDK 根目录，{@code null} 表示沿用当前进程 {@code JAVA_HOME}。
     */
    @Nullable
    public String getBuildJdkPath() {
        return buildJdkPath;
    }

    @NotNull
    public DeployTask setBuildJdkPath(@Nullable String buildJdkPath) {
        this.buildJdkPath = buildJdkPath == null || buildJdkPath.isBlank()
                ? null : buildJdkPath.trim();
        return this;
    }

    /**
     * 上传源解析后的本地路径。
     */
    @NotNull
    public File resolveLocalSource() {
        return com.hql.deployer.util.PathUtil.resolve(projectRoot, localPath);
    }

    /**
     * 是否需要执行构建命令。{@code UPLOAD_FILE} 模式直接上传，跳过构建。
     */
    public boolean requiresBuild() {
        // 「上传文件/目录」本来就没有构建步骤，无论是否仅打包都跳过
        if (buildMode == BuildMode.UPLOAD_FILE) {
            return false;
        }
        return switch (buildMode) {
            case MAVEN_BUILD -> true;
            case CUSTOM_COMMAND -> !customCommand.isBlank();
            case UPLOAD_FILE -> false;
        };
    }

    /**
     * 复制一份，ToolWindow 中「临时改服务器再跑」时避免污染已保存的 Run Configuration。
     */
    @NotNull
    public DeployTask copy() {
        return new DeployTask(projectRoot, host)
                .setBuildMode(buildMode)
                .setMavenModulePath(mavenModulePath)
                .setMavenGoals(mavenGoals)
                .setSkipTests(skipTests)
                .setCustomCommand(customCommand)
                .setCustomWorkingDir(customWorkingDir)
                .setLocalPath(localPath)
                .setExcludes(excludes)
                .setChmod(chmod)
                .setTargetDirectory(targetDirectory)
                .setPreUploadCommand(preUploadCommand)
                .setAfterDeployCommand(afterDeployCommand)
                .setStagingEnabled(stagingEnabled)
                .setPackageTimeoutMs(packageTimeoutMs)
                .setDisplayName(displayName)
                .setPackageOnly(packageOnly)
                .setBuildJdkPath(buildJdkPath);
    }
}
