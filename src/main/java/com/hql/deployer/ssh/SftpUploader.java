package com.hql.deployer.ssh;

import com.hql.deployer.core.DeployCancellation;
import com.hql.deployer.core.DeployException;
import com.hql.deployer.core.DeployStage;
import com.hql.deployer.util.ExcludeMatcher;
import com.hql.deployer.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 基于 SFTP 的递归上传器。
 *
 * <p>负责把本地源（单个文件或目录）完整传到远端目录。远端同名文件直接覆盖，
 * 不创建暂存目录，也不做上传后的数量/体积比对。</p>
 *
 * @author hql on 2026/9/28
 */
public final class SftpUploader {

    private SftpUploader() {
    }

    /**
     * 上传结果。
     *
     * @param fileCount 上传文件数
     * @param totalSize 上传总字节
     */
    public record UploadStats(int fileCount, long totalSize) {
    }

    /**
     * 上传到远端目录。
     *
     * @param localSource     本地源
     * @param remoteDirectory 远端目标目录（不存在会创建）
     * @param excludes        排除规则
     * @param chmod           远端权限，可为 {@code null}
     * @param progress        进度回调（已上传文件数，总大小），可为 {@code null}
     * @throws DeployException 上传过程出错
     */
    @NotNull
    public static UploadStats upload(@NotNull SshSession.SftpChannel channel,
                                     @NotNull File localSource,
                                     @NotNull String remoteDirectory,
                                     @Nullable List<String> excludes,
                                     @Nullable String chmod,
                                     @Nullable Consumer<Progress> progress) {
        return upload(channel, localSource, remoteDirectory, excludes, chmod, progress, null);
    }

    /**
     * 上传到远端目录，支持中途取消。
     *
     * <p>取消在两个层次生效：每个文件开始前检查一次（所有实现都支持），
     * 以及传输过程中按读块检查（实现方覆盖了可取消的 {@code put} 时生效，
     * 见 {@link SshSession.SftpChannel#put(File, String, String, DeployCancellation)}）。
     * 后者让单个大文件也能立即中断，而不必等它传完。</p>
     *
     * @param localSource     本地源
     * @param remoteDirectory 远端目标目录（不存在会创建）
     * @param excludes        排除规则
     * @param chmod           远端权限，可为 {@code null}
     * @param progress        进度回调（已上传文件数，总大小），可为 {@code null}
     * @param cancellation    取消信号，可为 {@code null}（不响应取消）
     * @throws DeployException 上传过程出错；用户取消时抛
     *         {@link com.hql.deployer.core.DeployCancelledException}
     */
    @NotNull
    public static UploadStats upload(@NotNull SshSession.SftpChannel channel,
                                     @NotNull File localSource,
                                     @NotNull String remoteDirectory,
                                     @Nullable List<String> excludes,
                                     @Nullable String chmod,
                                     @Nullable Consumer<Progress> progress,
                                     @Nullable DeployCancellation cancellation) {
        ExcludeMatcher matcher = ExcludeMatcher.of(excludes);
        String remoteDir = PathUtil.normalizeRemote(remoteDirectory);
        if (cancellation != null) {
            cancellation.throwIfCancelled(DeployStage.UPLOAD);
        }
        channel.mkdirs(remoteDir);

        if (localSource.isFile()) {
            String name = localSource.getName();
            String remotePath = remoteDir + "/" + name;
            try {
                channel.put(localSource, remotePath, chmod, cancellation);
            } catch (SshException e) {
                if (cancellation != null && cancellation.isCancelled()) {
                    cancellation.throwIfCancelled(DeployStage.UPLOAD);
                }
                throw new DeployException(DeployStage.UPLOAD, "上传文件失败: " + name + " → " + remoteDir, e);
            }
            if (progress != null) {
                progress.accept(new Progress(1, 1, name));
            }
            return new UploadStats(1, localSource.length());
        }

        if (!localSource.isDirectory()) {
            throw new DeployException(DeployStage.UPLOAD, "本地源不存在或不是文件/目录: " + localSource);
        }

        int[] uploadedCount = {0};
        long[] uploadedBytes = {0L};
        long totalBytes = PathUtil.collectStats(localSource, excludes).totalBytes();

        walk(localSource, localSource, matcher, cancellation, relativePath -> {
            File file = new File(localSource, relativePath);
            String remotePath = remoteDir + "/" + relativePath;
            String parent = remotePath.substring(0, remotePath.lastIndexOf('/'));
            try {
                channel.mkdirs(parent);
                channel.put(file, remotePath, chmod, cancellation);
            } catch (SshException e) {
                if (cancellation != null && cancellation.isCancelled()) {
                    cancellation.throwIfCancelled(DeployStage.UPLOAD);
                }
                throw new DeployException(DeployStage.UPLOAD,
                        "上传失败: " + relativePath + " → " + remotePath, e);
            }
            uploadedCount[0]++;
            uploadedBytes[0] += file.length();
            if (progress != null) {
                progress.accept(new Progress(uploadedCount[0], totalBytes, relativePath));
            }
        });

        return new UploadStats(uploadedCount[0], uploadedBytes[0]);
    }

    /**
     * 深度优先遍历待上传文件，目录自身被排除时整棵子树跳过。
     */
    private static void walk(@NotNull File root,
                             @NotNull File current,
                             @NotNull ExcludeMatcher matcher,
                             @Nullable DeployCancellation cancellation,
                             @NotNull Consumer<String> fileConsumer) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        // 排序让上传顺序稳定，便于日志比对与复现问题
        Arrays.sort(children, java.util.Comparator.comparing(File::getName));
        for (File child : children) {
            if (cancellation != null) {
                cancellation.throwIfCancelled(DeployStage.UPLOAD);
            }
            String relative = toRelative(root, child);
            if (matcher.isExcluded(relative)) {
                continue;
            }
            if (child.isDirectory()) {
                walk(root, child, matcher, cancellation, fileConsumer);
            } else if (child.isFile()) {
                fileConsumer.accept(relative);
            }
        }
    }

    @NotNull
    private static String toRelative(@NotNull File root, @NotNull File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(rootPath)) {
            String relative = filePath.substring(rootPath.length());
            while (relative.startsWith(File.separator) || relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative.replace('\\', '/');
        }
        return file.getName();
    }

    /**
     * 上传进度。
     *
     * @param uploadedFiles 已上传文件数
     * @param totalBytes    待上传总字节
     * @param currentFile   当前文件
     */
    public record Progress(int uploadedFiles, long totalBytes, @NotNull String currentFile) {
    }
}
