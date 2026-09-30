package com.hql.deployer.action;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.config.ServerProfileService;
import com.hql.deployer.pipeline.DeployTask;
import com.hql.deployer.runconfig.MavenDeployProgramRunner;
import com.hql.deployer.util.PathUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * 「打包选中模块」动作：右键项目视图中的模块目录，直接对该模块执行 Maven 打包。
 *
 * <p>不涉及远端，只跑本地构建。若项目没有配置服务器也不影响使用。</p>
 *
 * @author hql on 2026/9/28
 */
public final class PackageModuleAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getData(LangDataKeys.MODULE) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Module module = event.getData(LangDataKeys.MODULE);
        if (project == null || module == null) {
            return;
        }

        File projectRoot = resolveProjectRoot(project);
        if (projectRoot == null) {
            return;
        }

        ServerProfileService service = ServerProfileService.getInstance();
        // 打包不需要真实服务器，这里给一个占位配置，仅用于满足 DeployTask 的非空约束
        ServerProfile placeholder = new ServerProfile();
        placeholder.setName("<仅打包>");
        placeholder.setHostList("127.0.0.1");
        placeholder.setUsername("nobody");

        String relativePath = relativeModulePath(projectRoot, module);
        DeployTask task = new DeployTask(projectRoot, placeholder)
                .setMavenModulePath(relativePath)
                .setMavenGoals("clean package")
                .setSkipTests(true)
                .setLocalPath("target")
                .setPackageOnly(true)
                .setDisplayName(module.getName() + " 打包");

        MavenDeployProgramRunner.runInRunToolWindow(project, List.of(task), true);
    }

    /**
     * 项目根目录。优先用 {@code getBasePath()}，再退回项目文件所在目录。
     */
    @Nullable
    private static File resolveProjectRoot(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            return new File(basePath);
        }
        VirtualFile projectFile = project.getProjectFile();
        if (projectFile != null) {
            VirtualFile parent = projectFile.getParent();
            if (parent != null) {
                return VfsUtilCore.virtualToIoFile(parent);
            }
        }
        VirtualFile guessed = ProjectUtil.guessProjectDir(project);
        return guessed == null ? null : VfsUtilCore.virtualToIoFile(guessed);
    }

    /**
     * 模块内容根相对项目根的路径，作为 Maven 模块路径。
     */
    @NotNull
    private static String relativeModulePath(@NotNull File projectRoot, @NotNull Module module) {
        VirtualFile contentRoot = java.util.Arrays.stream(
                        ModuleRootManager.getInstance(module).getContentRoots())
                .filter(VirtualFile::isDirectory)
                .findFirst()
                .orElse(null);
        if (contentRoot == null) {
            return "";
        }
        String relative = PathUtil.relativeTo(projectRoot, VfsUtilCore.virtualToIoFile(contentRoot));
        return relative == null ? "" : relative;
    }
}
