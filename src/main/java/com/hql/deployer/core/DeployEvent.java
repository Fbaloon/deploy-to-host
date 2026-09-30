package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 流水线向 UI 推送的事件。
 *
 * @param stage 所属阶段
 * @param level 日志级别
 * @param text  文本内容
 * @author hql on 2026/9/28
 */
public record DeployEvent(@NotNull DeployStage stage,
                          @NotNull Level level,
                          @NotNull String text) {

    public enum Level {
        /** 普通输出 */
        INFO,
        /** 命令回显 */
        COMMAND,
        /** 成功提示 */
        SUCCESS,
        /** 警告 */
        WARN,
        /** 失败提示 */
        ERROR
    }

    @NotNull
    public static DeployEvent info(@NotNull DeployStage stage, @NotNull String text) {
        return new DeployEvent(stage, Level.INFO, text);
    }

    @NotNull
    public static DeployEvent command(@NotNull DeployStage stage, @NotNull String text) {
        return new DeployEvent(stage, Level.COMMAND, text);
    }

    @NotNull
    public static DeployEvent success(@NotNull DeployStage stage, @NotNull String text) {
        return new DeployEvent(stage, Level.SUCCESS, text);
    }

    @NotNull
    public static DeployEvent warn(@NotNull DeployStage stage, @NotNull String text) {
        return new DeployEvent(stage, Level.WARN, text);
    }

    @NotNull
    public static DeployEvent error(@NotNull DeployStage stage, @NotNull String text) {
        return new DeployEvent(stage, Level.ERROR, text);
    }

    /**
     * 将多行文本拆成多个事件，便于 UI 逐行渲染而不破坏换行符显示。
     */
    @NotNull
    public static java.util.List<DeployEvent> lines(@NotNull DeployStage stage,
                                                     @NotNull Level level,
                                                     @Nullable String text) {
        java.util.List<DeployEvent> events = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return events;
        }
        for (String line : text.split("\r?\n", -1)) {
            if (!line.isEmpty()) {
                events.add(new DeployEvent(stage, level, line));
            }
        }
        return events;
    }
}
