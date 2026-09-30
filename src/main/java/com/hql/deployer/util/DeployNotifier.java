package com.hql.deployer.util;

import com.hql.deployer.config.ServerProfileService;
import com.hql.deployer.core.DeployResult;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 部署结果通知。
 *
 * <p>通知只做「完成/失败」的提示，不在通知上提供回滚入口——回滚涉及远端目录操作，
 * 必须由用户在确认后手动执行。</p>
 *
 * @author hql on 2026/9/28
 */
public final class DeployNotifier {

    /**
     * 与 {@code plugin.xml} 中 {@code notificationGroup} 的 id 保持一致。
     */
    private static final String GROUP_ID = "Deploy to Host";

    private DeployNotifier() {
    }

    /**
     * 部署结束通知。失败时附带备份目录提示。
     */
    public static void notifyDeployed(@NotNull Project project,
                                      @NotNull String taskName,
                                      @NotNull DeployResult result) {
        if (result.success()) {
            notify(project, taskName + " 部署完成",
                    "上传 " + result.uploadedFileCount() + " 个文件，耗时 "
                            + (result.elapsedMillis() / 1000) + " 秒", NotificationType.INFORMATION);
            return;
        }
        StringBuilder content = new StringBuilder(result.message());
        if (result.backupDirectory() != null) {
            content.append("\n备份目录: ").append(result.backupDirectory());
        }
        notify(project, taskName + " 部署失败", content.toString(), NotificationType.ERROR);
    }

    /**
     * 仅打包结束通知。
     */
    public static void notifyPackaged(@NotNull Project project,
                                      @NotNull String taskName,
                                      @NotNull DeployResult result) {
        if (result.success()) {
            notify(project, taskName + " 打包完成",
                    "耗时 " + (result.elapsedMillis() / 1000) + " 秒", NotificationType.INFORMATION);
        } else {
            notify(project, taskName + " 打包失败", result.message(), NotificationType.ERROR);
        }
    }

    /**
     * 发送通知。{@code project} 为 {@code null} 时按应用级通知处理。
     *
     * <p>用户在设置里关闭通知后，这里直接跳过。失败通知同样受开关控制——
     * 用户如果不想被打扰，部署结果以 Run 工具窗的控制台输出为准。</p>
     */
    public static void notify(@Nullable Project project,
                              @NotNull String title,
                              @NotNull String content,
                              @NotNull NotificationType type) {
        if (!ServerProfileService.getInstance().toDefaults().notifyEnabled) {
            return;
        }
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, type);
        notification.notify(project);
    }
}
