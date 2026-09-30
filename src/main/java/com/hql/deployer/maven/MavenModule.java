package com.hql.deployer.maven;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serializable;

/**
 * 扫描到的一个 Maven 模块。
 *
 * @param artifactId  模块 artifactId
 * @param name        模块显示名（无 name 时回退为 artifactId）
 * @param groupId     模块 groupId
 * @param packaging   打包类型
 * @param pomFile     pom.xml 绝对路径
 * @param moduleDir   模块所在目录
 * @param aggregator 是否为聚合模块（packaging=pom）
 * @author hql on 2026/9/28
 */
public record MavenModule(@NotNull String artifactId,
                           @NotNull String name,
                           @NotNull String groupId,
                           @NotNull String packaging,
                           @NotNull File pomFile,
                           @NotNull File moduleDir,
                           boolean aggregator) implements Serializable {

    private static final long serialVersionUID = 4218873016548877126L;

    /**
     * 父模块（packaging=pom）通常不含可部署产物，UI 上默认折叠或置灰。
     */
    public boolean isDeployable() {
        return !aggregator;
    }

    /**
     * 相对项目根的模块目录路径，供部署配置持久化（不存绝对路径，保证可移植）。
     */
    @NotNull
    public String relativePath(@NotNull File projectRoot) {
        String root = projectRoot.getAbsolutePath();
        String dir = moduleDir.getAbsolutePath();
        if (dir.equals(root)) {
            return "";
        }
        if (dir.startsWith(root + File.separator)) {
            return dir.substring(root.length() + 1).replace('\\', '/');
        }
        return dir.replace('\\', '/');
    }

    @NotNull
    public String getPathSeparator() {
        return moduleDir.getAbsolutePath();
    }

    @Override
    @NotNull
    public String toString() {
        return name;
    }

    /**
     * 目标目录的常见约定：jar/war 产物位于 {@code target}。
     */
    @Nullable
    public File defaultTargetDir() {
        return new File(moduleDir, "target");
    }
}
