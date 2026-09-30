package com.hql.deployer.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一次发布的最终结果。
 *
 * <p>用于「仅打包」和「一键部署」两种场景的共同返回结构。</p>
 *
 * @param success            是否成功
 * @param stage             完成的最后一个阶段（失败时为失败阶段）
 * @param message           结果描述
 * @param staged            是否启用了远端暂存目录
 * @param backupDirectory   激活阶段产生的远端备份目录，未产生时为 {@code null}
 * @param uploadedFileCount 实际上传的文件数
 * @param uploadedBytes     实际上传的字节数
 * @param elapsedMillis     耗时
 * @param cancelled         是否由用户主动取消
 * @author hql on 2026/9/28
 */
public record DeployResult(boolean success,
                           @NotNull DeployStage stage,
                           @NotNull String message,
                           boolean staged,
                           @Nullable String backupDirectory,
                           int uploadedFileCount,
                           long uploadedBytes,
                           long elapsedMillis,
                           boolean cancelled) {

    @NotNull
    public static DeployResult failure(@NotNull DeployStage stage,
                                       @NotNull String message,
                                       boolean staged,
                                       @Nullable String backupDirectory,
                                       int uploadedFileCount,
                                       long uploadedBytes,
                                       long elapsedMillis) {
        return new DeployResult(false, stage, message, staged, backupDirectory,
                uploadedFileCount, uploadedBytes, elapsedMillis, false);
    }

    @NotNull
    public static DeployResult success(@NotNull DeployStage stage,
                                       @NotNull String message,
                                       boolean staged,
                                       @Nullable String backupDirectory,
                                       int uploadedFileCount,
                                       long uploadedBytes,
                                       long elapsedMillis) {
        return new DeployResult(true, stage, message, staged, backupDirectory,
                uploadedFileCount, uploadedBytes, elapsedMillis, false);
    }

    /**
     * 用户主动取消。不算失败：{@code success} 保持 {@code false}，
     * 但 {@code cancelled} 供通知与日志区分「取消」和「出错」。
     */
    @NotNull
    public static DeployResult cancelled(@NotNull DeployStage stage,
                                        @NotNull String message,
                                        boolean staged,
                                        @Nullable String backupDirectory,
                                        int uploadedFileCount,
                                        long uploadedBytes,
                                        long elapsedMillis) {
        return new DeployResult(false, stage, message, staged, backupDirectory,
                uploadedFileCount, uploadedBytes, elapsedMillis, true);
    }
}
