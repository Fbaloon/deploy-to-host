package com.hql.deployer.maven;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * 解析构建 Maven 时应当使用的 JDK。
 *
 * <p>背景：Maven 启动脚本按 {@code JAVA_HOME} 选择 JDK。若本机 {@code JAVA_HOME} 指向
 * JDK 8，而项目 {@code pom.xml} 声明 {@code <java.version>24</java.version>}，
 * 编译阶段会以「无效的目标发行版: 24」失败。多 JDK 开发机上这是高频问题。</p>
 *
 * <p>优先级：IDEA 当前项目的 Project SDK → 无（回退系统 {@code JAVA_HOME}）。
 * 之所以选 Project SDK，是因为 IDEA 中 Maven 导入与运行默认就以它为准，
 * 用户无需为本插件额外配置。</p>
 *
 * @author hql on 2026/9/29
 */
public final class BuildJdkResolver {

    private BuildJdkResolver() {
    }

    /**
     * 取项目 SDK 指向的 JDK 根目录，用作构建子进程的 {@code JAVA_HOME}。
     *
     * @return JDK 根目录绝对路径；项目无 SDK 或路径非法时返回 {@code null}，表示沿用环境变量
     */
    @Nullable
    public static String resolve(@Nullable Project project) {
        if (project == null || project.isDisposed()) {
            return null;
        }
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk == null) {
            return null;
        }
        return jdkHomeOf(sdk.getHomePath());
    }

    /**
     * 校验并规范化 SDK 的 home 路径。
     *
     * <p>部分 SDK 的 home 指向 {@code <jdk>/jre}，而 {@code JAVA_HOME} 需要 JDK 根，
     * 因此这里在检测到 {@code jre} 目录时上提一级。</p>
     */
    @Nullable
    static String jdkHomeOf(@Nullable String homePath) {
        if (homePath == null || homePath.isBlank()) {
            return null;
        }
        File dir = new File(homePath.trim());
        if (!dir.isDirectory()) {
            return null;
        }
        if ("jre".equalsIgnoreCase(dir.getName())) {
            File parent = dir.getParentFile();
            if (parent != null && isJdkRoot(parent)) {
                return parent.getAbsolutePath();
            }
        }
        return isJdkRoot(dir) ? dir.getAbsolutePath() : null;
    }

    /**
     * 判断目录是否像一个可用的 JDK 根：必须能定位到 {@code bin/java} 可执行文件。
     */
    private static boolean isJdkRoot(@NotNull File dir) {
        String javaName = com.intellij.openapi.util.SystemInfo.isWindows ? "java.exe" : "java";
        return new File(new File(dir, "bin"), javaName).isFile();
    }
}
