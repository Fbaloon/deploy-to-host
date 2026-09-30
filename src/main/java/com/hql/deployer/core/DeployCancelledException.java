package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;

/**
 * 用户主动取消导致的终止。
 *
 * <p>与普通 {@link DeployException} 区分开，是为了让 UI 能给出「已取消」而不是「部署失败」：
 * 取消是用户自己的选择，不该弹出错误通知，也不该在日志里留下红色报错。</p>
 *
 * <p>取消仍然遵守「不影响线上」的契约：抛出前调用方需已完成暂存目录清理，
 * 激活阶段一旦开始就不再响应取消，避免出现「备份已建但未切换」的中间态。</p>
 *
 * @author hql on 2026/9/28
 */
public class DeployCancelledException extends DeployException {

    private static final long serialVersionUID = 2L;

    public DeployCancelledException(@NotNull DeployStage stage) {
        super(stage, "部署已被取消");
    }

    public DeployCancelledException(@NotNull DeployStage stage, @NotNull String message) {
        super(stage, message);
    }
}
