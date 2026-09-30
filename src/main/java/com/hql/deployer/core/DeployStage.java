package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 发布流水线的阶段。
 *
 * <p>流水线严格按声明顺序执行，任一阶段失败立即中止，后续阶段不再执行。</p>
 *
 * @author hql on 2026/9/28
 */
public enum DeployStage {

    PRECHECK("预检"),
    BUILD("构建"),
    PRE_COMMAND("上传前命令"),
    UPLOAD("上传"),
    ACTIVATE("生效"),
    POST_COMMAND("部署后命令"),

    /**
     * 流程终止标记。仅出现在 {@link DeployResult} 中，表示「以上阶段已全部走完」，
     * 不是一个可执行的阶段——流水线不会为它派发开始事件。
     */
    DONE("完成");

    private final String displayName;

    DeployStage(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 是否为可执行阶段。{@link #DONE} 只是结果标记，遍历阶段时需跳过。
     */
    public boolean isExecutable() {
        return this != DONE;
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
     * 解析阶段名，解析失败返回 {@code null} 而非抛异常，避免历史配置反序列化失败。
     */
    @Nullable
    public static DeployStage parse(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (DeployStage stage : values()) {
            if (stage.name().equalsIgnoreCase(name.trim())) {
                return stage;
            }
        }
        return null;
    }
}
