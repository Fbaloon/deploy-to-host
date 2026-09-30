package com.hql.deployer.ssh;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.core.DeployCancellation;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SSH 能力抽象接口。
 *
 * <p>抽成接口有两个目的：</p>
 * <ul>
 *   <li>JSch 库只在实现类出现，测试可注入假实现离线验证流水线</li>
 *   <li>后续要支持私钥、跳板机时只扩展实现，不动流水线</li>
 * </ul>
 *
 * @author hql on 2026/9/28
 */
public interface SshSession extends AutoCloseable {

    /**
     * 建立连接。多主机配置下按声明顺序尝试，成功后不再重试。
     *
     * @param profile  服务器配置
     * @param password 密码
     * @return 实际连上的主机
     * @throws SshException 连接或认证失败
     */
    @NotNull
    String connect(@NotNull ServerProfile profile, @NotNull String password);

    /**
     * 校验连接可用。
     */
    boolean isConnected();

    /**
     * 创建 SFTP 通道。
     */
    @NotNull
    SftpChannel openSftp();

    /**
     * 执行远端命令并等待结束。
     *
     * @param command        命令文本
     * @param workingDir     工作目录，可为 {@code null}
     * @param timeoutMs      超时（毫秒），小于等于 0 表示不限制
     * @param outputListener 实时输出回调，可为 {@code null}
     */
    @NotNull
    RemoteCommandResult exec(@NotNull String command,
                             String workingDir,
                             int timeoutMs,
                             OutputListener outputListener);

    /**
     * 执行远端命令并等待结束，支持中途取消。
     *
     * <p>默认实现忽略取消信号，退化为普通 {@link #exec}。实现方若持有阻塞等待逻辑
     * （如轮询通道关闭）应覆盖本方法，在等待循环中检查信号并主动断开通道。</p>
     *
     * @param command        命令文本
     * @param workingDir     工作目录，可为 {@code null}
     * @param timeoutMs      超时（毫秒），小于等于 0 表示不限制
     * @param outputListener 实时输出回调，可为 {@code null}
     * @param cancellation   取消信号，可为 {@code null}（不响应取消）
     */
    @NotNull
    default RemoteCommandResult exec(@NotNull String command,
                                     String workingDir,
                                     int timeoutMs,
                                     OutputListener outputListener,
                                     @Nullable DeployCancellation cancellation) {
        return exec(command, workingDir, timeoutMs, outputListener);
    }

    /**
     * 关闭连接。重复调用安全。
     */
    @Override
    void close();

    /**
     * 远端输出回调。
     */
    @FunctionalInterface
    interface OutputListener {
        void onOutput(@NotNull String text);
    }

    /**
     * SFTP 能力抽象，只暴露插件需要的操作。
     */
    interface SftpChannel extends AutoCloseable {

        /**
         * 递归创建远端目录（已存在不报错）。
         */
        void mkdirs(@NotNull String remotePath);

        /**
         * 上传单个文件。
         *
         * @param localFile  本地文件
         * @param remotePath 远端绝对路径
         * @param mode       远端权限（如 {@code 0644}），可为 {@code null}
         */
        void put(@NotNull File localFile, @NotNull String remotePath, String mode);

        /**
         * 上传单个文件，支持中途取消。
         *
         * <p>默认实现忽略取消信号，退化为 {@link #put(File, String, String)}：此时取消
         * 只在文件开始前生效，当前文件会完整传完。实现方若能边读边检查信号（如把取消
         * 检查包进输入流），应覆盖本方法，让单个大文件也能立即中断。</p>
         *
         * @param localFile    本地文件
         * @param remotePath   远端绝对路径
         * @param mode         远端权限（如 {@code 0644}），可为 {@code null}
         * @param cancellation 取消信号，可为 {@code null}
         * @throws SshException 传输失败或被取消
         */
        default void put(@NotNull File localFile,
                         @NotNull String remotePath,
                         String mode,
                         @Nullable DeployCancellation cancellation) {
            put(localFile, remotePath, mode);
        }

        /**
         * 删除远端文件或目录（递归）。
         *
         * @return 是否成功（路径不存在视为成功）
         */
        boolean removeAll(@NotNull String remotePath);

        /**
         * 判断远端路径是否存在。
         */
        boolean exists(@NotNull String remotePath);

        /**
         * 重命名/移动。同一文件系统内为原子操作，用于「暂存目录 → 目标目录」的瞬间切换。
         *
         * @throws SshException 源不存在或目标已存在
         */
        void rename(@NotNull String sourcePath, @NotNull String targetPath);

        /**
         * 统计远端目录下常规文件的数量与总字节数。
         */
        Stats statDirectory(@NotNull String remotePath);

        /**
         * 列出远端目录下的直接子项名称，不含 {@code .} 与 {@code ..}。
         *
         * <p>用于清理历史备份这类「先列举再挑选」的场景。不含递归：调用方要自己
         * 判断每项是文件还是目录。列不出内容时返回空列表而非抛异常——清理场景下
         * 「列不出来」等价于「没有可清理项」，比中断部署更合理。</p>
         *
         * @param remotePath 远端绝对路径
         */
        @NotNull
        default List<String> listDirectory(@NotNull String remotePath) {
            return List.of();
        }

        /**
         * 切换工作目录。
         */
        void cd(@NotNull String remotePath);

        @Override
        void close();

        /**
         * 远端目录统计。
         *
         * @param fileCount 文件数
         * @param totalSize 总字节数
         */
        record Stats(long fileCount, long totalSize) {
        }
    }

    /**
     * 把 {@link java.util.List} 拼成空格分隔的命令字符串。
     */
    @NotNull
    static String joinCommand(@NotNull List<String> tokens) {
        return String.join(" ", tokens);
    }

    /**
     * 默认输出编解码。
     */
    java.nio.charset.Charset CHARSET = StandardCharsets.UTF_8;

    /**
     * 工具：把本地文件转为远端 POSIX 相对路径分隔符。
     */
    @NotNull
    static String toRemoteSeparators(@NotNull String path) {
        return FileUtil.toSystemIndependentName(path);
    }
}
