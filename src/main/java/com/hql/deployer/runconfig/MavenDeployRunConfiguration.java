package com.hql.deployer.runconfig;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.config.ServerProfileService;
import com.hql.deployer.core.DeployException;
import com.hql.deployer.core.DeployStage;
import com.hql.deployer.pipeline.DeployTask;
import com.hql.deployer.util.ExcludeMatcher;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 一条「部署到服务器」配置。
 *
 * <p>字段分组与 Alibaba Cloud Toolkit 保持一致：部署内容 / 目标主机 / 目标目录 / 上传前命令 / 部署后命令。
 * 其中「部署内容」提供三种模式，覆盖「是否打包」与「可能上传静态文件」两种诉求：</p>
 * <ul>
 *   <li>{@link BuildMode#MAVEN_BUILD} 先 Maven 打包再上传模块产物</li>
 *   <li>{@link BuildMode#UPLOAD_FILE} 不打包，直接上传指定文件/目录（静态资源场景）</li>
 *   <li>{@link BuildMode#CUSTOM_COMMAND} 执行任意本地构建命令（npm / gradle / sh）</li>
 * </ul>
 *
 * @author hql on 2026/9/28
 */
public final class MavenDeployRunConfiguration extends LocatableConfigurationBase<Element> {

    /**
     * 持久化字段。字段名与 {@code @OptionTag} 共同决定 XML 结构，修改会影响老配置加载。
     */
    public static final class Options {
        @OptionTag
        public String buildMode = BuildMode.MAVEN_BUILD.name();
        @OptionTag
        public String mavenModulePath = "";
        @OptionTag
        public String mavenGoals = "clean package";
        @OptionTag
        public boolean skipTests = true;
        @OptionTag
        public String customCommand = "";
        @OptionTag
        public String customWorkingDir = "";
        @OptionTag
        public String localPath = "target";
        @OptionTag
        public String excludes = "";
        @OptionTag
        public String chmod = "";
        /**
         * 旧版单服务器配置，仅兼容旧配置加载；新代码以 {@link #hostIds} 为准。
         */
        @OptionTag
        public String hostId = "";
        /**
         * 选中的服务器 id，多个 id 用换行分隔（多负载部署时一配置多主机）。
         */
        @OptionTag
        public String hostIds = "";
        @OptionTag
        public String targetDirectory = "";
        @OptionTag
        public String preUploadCommand = "";
        @OptionTag
        public String afterDeployCommand = "";
        @OptionTag
        public boolean stagingEnabled = true;
        @OptionTag
        public int packageTimeoutMs = 600_000;
    }

    private Options options = new Options();

    public MavenDeployRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory) {
        super(project, factory);
    }

    @Override
    public void readExternal(@NotNull Element element) {
        XmlSerializer.deserializeInto(options, element);
        applyDefaults();
    }

    @Override
    public void writeExternal(@NotNull Element element) {
        XmlSerializer.serializeInto(options, element);
    }

    @Override
    public @Nullable SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new MavenDeployConfigurationEditor(getProject());
    }

    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        // 实际执行在 MavenDeployProgramRunner 中完成，这里不提供状态
        return null;
    }

    /**
     * 本配置的自定义选项。
     *
     * <p>方法名不能叫 {@code getOptions()}——父类 {@code LocatableConfigurationBase}
     * 已用该名返回 {@code LocatableRunConfigurationOptions}，重名会因返回类型不兼容而无法编译。</p>
     */
    @NotNull
    public Options getDeployOptions() {
        return options;
    }

    public void setDeployOptions(@NotNull Options options) {
        this.options = options;
    }

    /**
     * 补齐空字段，避免旧版本配置反序列化后出现 {@code null}。
     */
    public void applyDefaults() {
        Options o = options;
        if (o.buildMode == null) {
            o.buildMode = BuildMode.MAVEN_BUILD.name();
        }
        if (o.mavenModulePath == null) {
            o.mavenModulePath = "";
        }
        if (o.mavenGoals == null) {
            o.mavenGoals = "clean package";
        }
        if (o.customCommand == null) {
            o.customCommand = "";
        }
        if (o.customWorkingDir == null) {
            o.customWorkingDir = "";
        }
        if (o.localPath == null) {
            o.localPath = "target";
        }
        if (o.excludes == null) {
            o.excludes = "";
        }
        if (o.chmod == null) {
            o.chmod = "";
        }
        if (o.hostId == null) {
            o.hostId = "";
        }
        if (o.hostIds == null) {
            o.hostIds = "";
        }
        // 旧版配置迁移：把单选的 hostId 并入 hostIds，避免老配置丢失目标主机
        if (o.hostIds.isBlank() && !o.hostId.isBlank()) {
            o.hostIds = o.hostId.trim();
        }
        if (o.targetDirectory == null) {
            o.targetDirectory = "";
        }
        if (o.preUploadCommand == null) {
            o.preUploadCommand = "";
        }
        if (o.afterDeployCommand == null) {
            o.afterDeployCommand = "";
        }
        if (o.packageTimeoutMs <= 0) {
            o.packageTimeoutMs = 600_000;
        }
    }

    @NotNull
    public BuildMode resolveBuildMode() {
        return BuildMode.parse(options.buildMode);
    }

    @NotNull
    public List<String> getExcludeList() {
        return ExcludeMatcher.splitRules(options.excludes);
    }

    /**
     * 补齐全选状态：选中的服务器 id 列表。
     *
     * <p>返回值永不为空——旧配置只有 {@link #hostId} 时降级为单元素列表；
     * 全空则返回空列表，由调用方按「未选主机」处理。</p>
     */
    @NotNull
    public List<String> getSelectedHostIds() {
        List<String> ids = ExcludeMatcher.splitRules(options.hostIds);
        if (ids.isEmpty() && !options.hostId.isBlank()) {
            ids = List.of(options.hostId.trim());
        }
        return ids;
    }

    /**
     * 校验选中主机是否全部存在。缺失时返回该配置描述用于提示。
     */
    @NotNull
    public List<String> validateSelectedHosts() {
        List<String> missing = new ArrayList<>();
        for (String id : getSelectedHostIds()) {
            if (ServerProfileService.getInstance().findById(id).isEmpty()) {
                missing.add(id);
            }
        }
        return missing;
    }

    /**
     * 组装成流水线可执行的任务列表（多服务器：一台一个任务）。
     *
     * @param packageOnly  仅执行构建不上传
     * @param hostOverride 临时覆盖目标服务器（ToolWindow 临时切服务器时用），
     *                     非 {@code null} 时忽略配置选中的主机，只部署这一台
     */
    @NotNull
    public List<DeployTask> toTasks(boolean packageOnly, @Nullable ServerProfile hostOverride) {
        ServerProfileService service = ServerProfileService.getInstance();
        ServerProfileService.Defaults defaults = service.toDefaults();
        List<DeployTask> tasks = new ArrayList<>();
        List<String> ids = hostOverride != null
                ? List.of(hostOverride.getId())
                : getSelectedHostIds();
        for (String id : ids) {
            ServerProfile host = service.findOrPlaceholder(id);
            tasks.add(new DeployTask(resolveProjectRoot(), host)
                    .setBuildMode(resolveBuildMode())
                    .setMavenModulePath(options.mavenModulePath)
                    .setMavenGoals(options.mavenGoals)
                    .setSkipTests(options.skipTests)
                    .setCustomCommand(options.customCommand)
                    .setCustomWorkingDir(options.customWorkingDir)
                    .setLocalPath(options.localPath)
                    .setExcludes(getExcludeList())
                    .setChmod(options.chmod)
                    .setTargetDirectory(options.targetDirectory)
                    .setPreUploadCommand(options.preUploadCommand)
                    .setAfterDeployCommand(options.afterDeployCommand)
                    .setStagingEnabled(options.stagingEnabled)
                    .setPackageTimeoutMs(options.packageTimeoutMs > 0
                            ? options.packageTimeoutMs : defaults.packageTimeoutMs)
                    .setDisplayName(getName())
                    .setPackageOnly(packageOnly));
        }
        return tasks;
    }

    /**
     * 便捷入口：返回 {@link #toTasks} 的第一个任务，供原「单服务器」语义的调用方使用。
     *
     * @param packageOnly  仅执行构建不上传
     * @param hostOverride 临时覆盖目标服务器，可不填
     */
    @NotNull
    public DeployTask toTask(boolean packageOnly, @Nullable ServerProfile hostOverride) {
        List<DeployTask> tasks = toTasks(packageOnly, hostOverride);
        if (tasks.isEmpty()) {
            throw new DeployException(DeployStage.PRECHECK, "未选择任何服务器");
        }
        return tasks.get(0);
    }

    /**
     * 项目根目录。优先用 {@code getBasePath()}；取不到时退回
     * {@code ProjectUtil} 的猜测（模块项目下 basePath 可能为空）。
     */
    @NotNull
    private File resolveProjectRoot() {
        String basePath = getProject().getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            return new File(basePath);
        }
        com.intellij.openapi.vfs.VirtualFile guessed =
                com.intellij.openapi.project.ProjectUtil.guessProjectDir(getProject());
        if (guessed != null) {
            return com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(guessed);
        }
        throw new DeployException(DeployStage.PRECHECK, "无法确定项目根目录，请在 Run 配置中检查项目路径");
    }

    /**
     * 复制配置（供 Run 面板的「复制」与 ToolWindow 的快速部署使用）。
     */
    @NotNull
    public MavenDeployRunConfiguration copyWithName(@NotNull String newName) {
        MavenDeployRunConfiguration copy = new MavenDeployRunConfiguration(getProject(), getFactory());
        copy.setName(newName);
        copy.options = new Options();
        XmlSerializerUtil.copyBean(options, copy.options);
        return copy;
    }

    /**
     * 深拷贝配置。
     *
     * <p>平台在快照校验、后台同步、isModified 对比时都会 clone 配置对象。
     * 父类的 {@code clone()} 只做浅拷贝——虽然会新建父类的 {@code RunConfigurationOptions}，
     * 但本类的 {@code options} 字段仍会与源对象共享同一个实例，导致
     * {@code checkEditorData} 写入快照时污染真实配置（未点 Apply/OK 也会落盘）。
     * 因此必须在这里对 options 做值拷贝，让快照完全独立。</p>
     */
    @Override
    @NotNull
    public MavenDeployRunConfiguration clone() {
        MavenDeployRunConfiguration copy = (MavenDeployRunConfiguration) super.clone();
        copy.options = new Options();
        XmlSerializerUtil.copyBean(options, copy.options);
        return copy;
    }

    /**
     * 配置是否完整到可以执行：目标目录非空，且选中的主机全部存在。
     */
    public boolean isComplete() {
        return !options.targetDirectory.trim().isEmpty()
                && validateSelectedHosts().isEmpty();
    }

    /**
     * 人类可读的摘要，显示在配置下拉里。多台主机时列出全部名称。
     */
    @NotNull
    public String describe() {
        ServerProfileService service = ServerProfileService.getInstance();
        List<String> names = getSelectedHostIds().stream()
                .map(id -> service.findById(id).map(ServerProfile::getName).orElse("<未知主机>"))
                .collect(Collectors.toList());
        String hostText = names.isEmpty() ? "<未选择主机>" : String.join(", ", names);
        return getName() + "  [" + resolveBuildMode().getDisplayName()
                + " → " + hostText + " " + options.targetDirectory + "]";
    }

    @Override
    @NotNull
    public String toString() {
        return getName();
    }
}
