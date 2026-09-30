package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 发布流程异常，携带失败发生的阶段，便于 UI 精确定位与提示。
 *
 * @author hql on 2026/9/28
 */
public class DeployException extends RuntimeException {

    private static final long serialVersionUID = 6841779236198273453L;

    private final transient DeployStage stage;

    public DeployException(@NotNull DeployStage stage, @NotNull String message) {
        this(stage, message, null);
    }

    public DeployException(@NotNull DeployStage stage, @NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    @NotNull
    public DeployStage getStage() {
        return stage;
    }

    /**
     * 面向用户的完整提示，格式为「[阶段] 原因」。
     */
    @NotNull
    public String toUserMessage() {
        return "[" + stage.getDisplayName() + "] " + getMessage();
    }
}
