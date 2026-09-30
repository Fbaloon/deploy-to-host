package com.hql.deployer.runconfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 部署内容类型，对齐 Alibaba Cloud Toolkit 的「Deploy File」字段。
 *
 * @author hql on 2026/9/28
 */
public enum BuildMode {

    /** 执行 Maven 构建后上传模块产物 */
    MAVEN_BUILD("Maven 构建"),

    /** 不执行构建，直接上传本地已有的文件或目录（静态资源场景） */
    UPLOAD_FILE("上传文件/目录"),

    /** 执行自定义构建命令（npm / gradle / sh 脚本等） */
    CUSTOM_COMMAND("自定义命令");

    private final String displayName;

    BuildMode(String displayName) {
        this.displayName = displayName;
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    @Override
    @NotNull
    public String toString() {
        return displayName;
    }

    /**
     * 解析持久化值，未知值回退到 {@link #MAVEN_BUILD}，保证老配置仍可加载。
     */
    @NotNull
    public static BuildMode parse(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return MAVEN_BUILD;
        }
        for (BuildMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name.trim())) {
                return mode;
            }
        }
        return MAVEN_BUILD;
    }
}
