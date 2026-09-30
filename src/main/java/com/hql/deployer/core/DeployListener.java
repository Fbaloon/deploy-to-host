package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;

/**
 * 流水线事件监听器。所有回调都在后台线程被调用，UI 层需自行切回 EDT。
 *
 * @author hql on 2026/9/28
 */
public interface DeployListener {

    /**
     * 阶段开始。
     */
    default void onStageStart(@NotNull DeployStage stage) {
    }

    /**
     * 阶段内的文本输出。
     */
    default void onEvent(@NotNull DeployEvent event) {
    }

    /**
     * 整个流程成功结束。
     */
    default void onSuccess(@NotNull DeployResult result) {
    }

    /**
     * 整个流程失败结束（快速失败中止）。
     */
    default void onFailure(@NotNull DeployException exception) {
    }

    /**
     * 用户主动取消。
     */
    default void onCancelled() {
    }

    /**
     * 空实现，便于按需覆盖。
     */
    final class NoOp implements DeployListener {
        public static final NoOp INSTANCE = new NoOp();

        @Override
        public String toString() {
            return "DeployListener.NoOp";
        }
    }
}
