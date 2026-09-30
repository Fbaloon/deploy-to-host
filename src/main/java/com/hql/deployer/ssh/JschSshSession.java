package com.hql.deployer.ssh;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.core.DeployCancellation;
import com.hql.deployer.core.DeployCancelledException;
import com.hql.deployer.core.DeployStage;
import com.hql.deployer.util.PathUtil;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JSch 的 {@link SshSession} 实现，仅支持密码认证。
 *
 * <p>关键行为：</p>
 * <ul>
 *   <li>连接前把 {@code PreferredAuthentications} 限定为 password，避免服务端只提供
 *       publickey 时在 agent 上做无谓等待，缩短失败反馈时间</li>
 *   <li>关闭 StrictHostKeyChecking 的交互提示，采用「不校验」策略（内网自建服务器常见），
 *       用户可通过服务器配置自行改为校验</li>
 *   <li>命令输出用流式读取而非 {@code getInputStream().readAllBytes()}，避免无超时挂死</li>
 * </ul>
 *
 * @author hql on 2026/9/28
 */
public final class JschSshSession implements SshSession {

    private static final Logger LOG = Logger.getInstance(JschSshSession.class);

    /**
     * 打开后会把 JSch 的协议级日志（算法协商、认证方法、断开时机）写入 idea.log。
     * 用法：在 Run Configuration 的 VM options 里加 {@code -Dmaven.quick.deploy.ssh.debug=true}，
     * 或直接设置到运行 IDE 的 JVM 参数上。默认只放行 INFO 以上，避免刷屏。
     */
    private static final String SSH_DEBUG_PROPERTY = "maven.quick.deploy.ssh.debug";

    static {
        // JSch 默认把日志丢进 slf4j，而插件运行环境没有绑定实现，等于全部静默丢弃。
        // 接上 IDE 的 Logger，连接问题才有迹可循。
        JSch.setLogger(new IntelliJJschLogger());
    }

    private static final class IntelliJJschLogger implements com.jcraft.jsch.Logger {

        private final boolean debug = Boolean.getBoolean(SSH_DEBUG_PROPERTY);

        @Override
        public boolean isEnabled(int level) {
            return debug || level >= com.jcraft.jsch.Logger.INFO;
        }

        @Override
        public void log(int level, String message) {
            String line = "[JSch] " + message;
            if (level >= com.jcraft.jsch.Logger.ERROR) {
                LOG.error(line);
            } else if (level >= com.jcraft.jsch.Logger.WARN) {
                LOG.warn(line);
            } else if (level >= com.jcraft.jsch.Logger.INFO) {
                LOG.info(line);
            } else {
                LOG.debug(line);
            }
        }
    }

    private Session session;
    private String connectedHost;

    @Override
    @NotNull
    public String connect(@NotNull ServerProfile profile, @NotNull String password) {
        List<String> hosts = profile.getHosts();
        if (hosts.isEmpty()) {
            throw new SshException("主机列表为空，未配置任何服务器地址");
        }
        if (password.isEmpty()) {
            throw new SshException("未配置密码，无法登录 " + profile.getName()
                    + "。请到 设置 → 构建、部署与发布 → Deploy to Host 中编辑该服务器并填写密码后重试");
        }

        List<String> failures = new ArrayList<>();
        for (String host : hosts) {
            try {
                doConnect(profile, host, password);
                connectedHost = host;
                return host;
            } catch (JSchException e) {
                // 保留完整堆栈，JSch 的 message 往往丢掉了真正的根因
                LOG.warn("SSH 连接失败: " + profile.getUsername() + "@" + host + ":" + profile.getPort(), e);
                failures.add(host + ": " + describe(e, profile.getPort()));
            }
        }
        throw new SshException("连接失败（已尝试 " + hosts.size() + " 个地址）：" + String.join("; ", failures));
    }

    private void doConnect(@NotNull ServerProfile profile, @NotNull String host, @NotNull String password)
            throws JSchException {
        JSch jsch = new JSch();
        Session newSession = jsch.getSession(profile.getUsername(), host, profile.getPort());

        Properties config = new Properties();
        // 明确只要密码，避免服务端配置了 publickey 时先卡在 agent 上
        config.put("PreferredAuthentications", "password");
        // 内网自建服务器普遍未分发 known_hosts，采用免校验；如需严格校验可改为 ask
        config.put("StrictHostKeyChecking", "no");
        // 保持连接以便连续执行多个命令
        config.put("ServerAliveInterval", "15000");
        config.put("ServerAliveCountMax", "3");
        newSession.setConfig(config);
        newSession.setPassword(password);

        newSession.connect(Math.max(1000, profile.getConnectTimeoutMs()));
        this.session = newSession;
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    @NotNull
    private Session requireSession() {
        if (session == null || !session.isConnected()) {
            throw new SshException("SSH 连接已断开");
        }
        return session;
    }

    @Override
    @NotNull
    public SftpChannel openSftp() {
        Session current = requireSession();
        try {
            ChannelSftp channel = (ChannelSftp) current.openChannel("sftp");
            channel.connect(15_000);
            return new JschSftpChannel(channel);
        } catch (JSchException e) {
            throw new SshException("打开 SFTP 通道失败: " + rootMessage(e), e);
        }
    }

    @Override
    @NotNull
    public RemoteCommandResult exec(@NotNull String command,
                                    String workingDir,
                                    int timeoutMs,
                                    OutputListener outputListener) {
        return exec(command, workingDir, timeoutMs, outputListener, null);
    }

    @Override
    @NotNull
    public RemoteCommandResult exec(@NotNull String command,
                                    String workingDir,
                                    int timeoutMs,
                                    OutputListener outputListener,
                                    @Nullable DeployCancellation cancellation) {
        Session current = requireSession();
        ChannelExec channel = null;
        ExecutorService pool = AppExecutorUtil.getAppExecutorService();
        try {
            channel = (ChannelExec) current.openChannel("exec");
            // 把工作目录编进命令，避免依赖 channel 状态，命令文本对用户可见更易排查
            String fullCommand = buildCommand(command, workingDir);
            channel.setCommand(fullCommand);
            channel.setPty(false);
            // 远端常配了彩色输出提示，关闭 TERM 避免日志夹杂 ANSI 转义序列
            channel.setEnv("TERM", "dumb");

            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            channel.connect(10_000);

            // 必须用独立线程读流：Socket 读在没有数据时会阻塞，循环里检查 deadline 并不管用
            Future<String> outFuture = pool.submit(() -> readStream(stdout, outputListener));
            Future<String> errFuture = pool.submit(() -> readStream(stderr, outputListener));

            CloseWait wait = waitForClose(channel, timeoutMs, cancellation);
            if (!wait.completed()) {
                // 超时或取消：断开通道让读流线程随之结束，避免线程泄漏
                try {
                    channel.disconnect();
                } catch (RuntimeException ignored) {
                    // 已断开
                }
                if (wait.cancelled()) {
                    // 断开只是本地不再等待，远端进程可能还在跑——必须说清楚，
                    // 否则用户会以为命令没执行而重复触发。exec 是通用命令通道，
                    // 何用途的命令被取消都应提示「命令」，而非绑定部署后命令
                    throw new DeployCancelledException(DeployStage.POST_COMMAND,
                            "远端命令已取消等待（远端进程可能仍在执行）: " + fullCommand);
                }
                throw new SshException("远端命令执行超时（" + timeoutMs + " ms）已终止: " + fullCommand);
            }

            String out = outFuture.get(5, TimeUnit.SECONDS);
            String err = errFuture.get(5, TimeUnit.SECONDS);
            int exitCode = channel.getExitStatus() == -1 ? 0 : channel.getExitStatus();
            return new RemoteCommandResult(exitCode, out, err);
        } catch (JSchException e) {
            throw new SshException("执行远端命令失败: " + rootMessage(e), e);
        } catch (java.io.IOException e) {
            throw new SshException("读取远端命令输出流失败: " + rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshException("执行远端命令被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new SshException("读取远端命令输出失败: " + rootMessage(e.getCause() == null ? e : e.getCause()),
                    e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new SshException("读取远端命令输出超时");
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * 等待命令通道关闭的结论。
     *
     * @param completed 正常结束
     * @param cancelled 因取消而结束
     */
    private record CloseWait(boolean completed, boolean cancelled) {
    }

    /**
     * 等待命令通道关闭。同时盯住取消信号与截止时间，两者任一命中即返回。
     */
    private static CloseWait waitForClose(@NotNull ChannelExec channel,
                                          int timeoutMs,
                                          @Nullable DeployCancellation cancellation)
            throws InterruptedException {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (!channel.isClosed()) {
            if (cancellation != null && cancellation.isCancelled()) {
                return new CloseWait(false, true);
            }
            if (System.currentTimeMillis() > deadline) {
                return new CloseWait(false, false);
            }
            Thread.sleep(30);
        }
        return new CloseWait(true, false);
    }

    /**
     * 组装带工作目录的命令。
     */
    @NotNull
    static String buildCommand(@NotNull String command, @Nullable String workingDir) {
        String trimmed = command.trim();
        if (workingDir == null || workingDir.isBlank()) {
            return trimmed;
        }
        return "cd " + PathUtil.shellQuote(workingDir) + " && " + trimmed;
    }

    /**
     * 读到流结束为止，边读边按行转发给 UI。
     */
    @NotNull
    private static String readStream(@NotNull InputStream stream, @Nullable OutputListener outputListener) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        StringBuilder pending = new StringBuilder();
        try {
            while (true) {
                int read = stream.read(chunk);
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                if (outputListener != null) {
                    pending.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                    int newlineIndex;
                    while ((newlineIndex = pending.indexOf("\n")) >= 0) {
                        outputListener.onOutput(pending.substring(0, newlineIndex).stripTrailing());
                        pending.delete(0, newlineIndex + 1);
                    }
                }
            }
        } catch (java.io.IOException e) {
            // 通道被断开时会走到这里，属于超时路径的正常收尾
            LOG.debug("读取远端输出流结束", e);
        }
        if (outputListener != null && !pending.isEmpty()) {
            outputListener.onOutput(pending.toString().stripTrailing());
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (RuntimeException e) {
                LOG.debug("关闭 SSH 连接时异常", e);
            } finally {
                session = null;
                connectedHost = null;
            }
        }
    }

    @Nullable
    public String getConnectedHost() {
        return connectedHost;
    }

    @NotNull
    private static String rootMessage(@NotNull Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * 把连接失败翻译成可执行的排查建议。
     *
     * <p>JSch 抛出的英文原文（如 {@code connection is closed by foreign host}）
     * 几乎不携带原因，直接抛给用户等于没有信息。这里保留原始异常链，
     * 再按已知特征追加中文说明，避免用户只能靠猜。</p>
     */
    @NotNull
    private static String describe(@NotNull Throwable throwable, int port) {
        StringBuilder chain = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) {
                chain.append(" <- ");
            }
            chain.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                chain.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        String text = chain.toString();
        String lower = text.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("auth fail") || lower.contains("userauth fail")
                || lower.contains("invalid credentials") || lower.contains("authentication")) {
            return text + "（服务器已确认可用密码认证，此处为用户名或密码错误）";
        }
        if (lower.contains("closed by foreign host")) {
            return text + "（服务器在握手或认证之后主动断开。密码错误会返回 Auth fail，"
                    + "出现此提示说明断开来得更晚：可能是 root 密码过期被要求改密、"
                    + "服务器资源耗尽、sshd 的 AllowUsers/MaxSessions 限制，"
                    + "或客户端与服务端的算法协商被中断。建议先用系统 ssh 客户端交叉验证："
                    + "ssh -p " + port + " 用户名@主机）";
        }
        if (lower.contains("algorithm negotiation fail") || lower.contains("kex")) {
            return text + "（客户端与服务端在算法协商阶段失败，通常是主机密钥或 KEX 算法不兼容，"
                    + "可尝试更换 jsch 版本）";
        }
        if (lower.contains("unknownhostkey") || lower.contains("hostkey")) {
            return text + "（服务器主机密钥校验失败，可能是服务器重装过或中间人）";
        }
        if (lower.contains("connection refused")) {
            return text + "（端口拒绝连接，请确认端口号与 sshd 是否在监听）";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return text + "（连接超时，请检查网络、防火墙与安全组是否放行该端口）";
        }
        return text;
    }

    /**
     * JSch SFTP 通道封装。
     */
    private static final class JschSftpChannel implements SftpChannel {

        private final ChannelSftp channel;

        private JschSftpChannel(@NotNull ChannelSftp channel) {
            this.channel = channel;
        }

        @Override
        public void mkdirs(@NotNull String remotePath) {
            String normalized = PathUtil.normalizeRemote(remotePath);
            if (normalized.isEmpty() || "/".equals(normalized)) {
                return;
            }
            String[] parts = normalized.startsWith("/")
                    ? normalized.substring(1).split("/")
                    : normalized.split("/");
            StringBuilder current = new StringBuilder(normalized.startsWith("/") ? "/" : "");
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                if (current.length() > 0 && !current.toString().endsWith("/")) {
                    current.append('/');
                }
                current.append(part);
                String path = current.toString();
                if (exists(path)) {
                    continue;
                }
                try {
                    channel.mkdir(path);
                } catch (SftpException e) {
                    // 并发下可能已被创建，忽略「已存在」
                    if (e.id != ChannelSftp.SSH_FX_FAILURE || !exists(path)) {
                        throw new SshException("创建远端目录失败 " + path + ": " + e.getMessage(), e);
                    }
                }
            }
        }

        @Override
        public void put(@NotNull File localFile, @NotNull String remotePath, String mode) {
            put(localFile, remotePath, mode, null);
        }

        @Override
        public void put(@NotNull File localFile,
                        @NotNull String remotePath,
                        String mode,
                        @Nullable DeployCancellation cancellation) {
            String remoteDir = remotePath.contains("/")
                    ? remotePath.substring(0, remotePath.lastIndexOf('/'))
                    : ".";
            mkdirs(".".equals(remoteDir) ? "." : remoteDir);
            if (cancellation == null) {
                putFile(localFile, remotePath);
                applyMode(remotePath, mode);
                return;
            }
            // 把取消检查塞进输入流：JSch 的 put 循环边读边写，
            // 流一旦抛异常就会中断传输，单个几百 MB 的 jar 也能秒停
            try (InputStream in = new CancellingInputStream(
                    new java.io.FileInputStream(localFile), cancellation)) {
                channel.put(in, remotePath, ChannelSftp.OVERWRITE);
            } catch (SftpException | java.io.IOException | RuntimeException e) {
                // 不能靠异常类型判断取消：JSch 的 put(InputStream,...) 会把所有异常
                // 一律包成 SftpException，类型信息在这里已经丢失，只能看信号本身
                if (cancellation.isCancelled()) {
                    throw new SshException("上传已取消: " + localFile.getName(), e);
                }
                throw new SshException("上传文件失败 " + localFile.getName() + " -> " + remotePath
                        + ": " + rootMessage(e), e);
            }
            // 传完再设权限：取消路径不应该留下改了一半权限的远端文件
            applyMode(remotePath, mode);
        }

        private void putFile(@NotNull File localFile, @NotNull String remotePath) {
            try {
                channel.put(localFile.getAbsolutePath(), remotePath, ChannelSftp.OVERWRITE);
            } catch (SftpException e) {
                throw new SshException("上传文件失败 " + localFile.getName() + " -> " + remotePath
                        + ": " + e.getMessage(), e);
            }
        }

        private void applyMode(@NotNull String remotePath, @Nullable String mode) {
            if (mode == null || mode.isBlank()) {
                return;
            }
            try {
                channel.chmod(parseOctal(mode), remotePath);
            } catch (SftpException e) {
                throw new SshException("设置远端权限失败 " + remotePath + ": " + e.getMessage(), e);
            } catch (NumberFormatException e) {
                throw new SshException("非法的权限值: " + mode);
            }
        }

        private static int parseOctal(@NotNull String mode) {
            String trimmed = mode.trim();
            if (trimmed.length() > 2 && trimmed.charAt(0) == '0') {
                trimmed = trimmed.substring(1);
            }
            return Integer.parseInt(trimmed, 8);
        }

        @Override
        public boolean removeAll(@NotNull String remotePath) {
            String normalized = PathUtil.normalizeRemote(remotePath);
            if (normalized.isEmpty() || "/".equals(normalized)) {
                throw new SshException("拒绝删除根路径");
            }
            try {
                SftpATTRS attrs = channel.lstat(normalized);
                if (attrs.isDir()) {
                    for (ChannelSftp.LsEntry entry : listOrEmpty(normalized)) {
                        String name = entry.getFilename();
                        if (".".equals(name) || "..".equals(name)) {
                            continue;
                        }
                        removeAll(normalized + "/" + name);
                    }
                    channel.rmdir(normalized);
                } else {
                    channel.rm(normalized);
                }
                return true;
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return true;
                }
                throw new SshException("删除远端路径失败 " + remotePath + ": " + e.getMessage(), e);
            }
        }

        @Override
        public boolean exists(@NotNull String remotePath) {
            try {
                channel.lstat(PathUtil.normalizeRemote(remotePath));
                return true;
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return false;
                }
                throw new SshException("检查远端路径失败 " + remotePath + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void rename(@NotNull String sourcePath, @NotNull String targetPath) {
            String source = PathUtil.normalizeRemote(sourcePath);
            String target = PathUtil.normalizeRemote(targetPath);
            String parent = target.contains("/") ? target.substring(0, target.lastIndexOf('/')) : ".";
            if (!".".equals(parent)) {
                mkdirs(parent);
            }
            try {
                channel.rename(source, target);
            } catch (SftpException e) {
                throw new SshException("重命名远端路径失败 " + sourcePath + " → " + targetPath
                        + ": " + e.getMessage(), e);
            }
        }

        @Override
        public Stats statDirectory(@NotNull String remotePath) {
            String normalized = PathUtil.normalizeRemote(remotePath);
            long fileCount = 0;
            long totalSize = 0;
            if (!exists(normalized)) {
                return new Stats(0, 0);
            }
            try {
                SftpATTRS attrs = channel.lstat(normalized);
                if (!attrs.isDir()) {
                    return new Stats(1, attrs.getSize());
                }
            } catch (SftpException e) {
                throw new SshException("读取远端目录属性失败 " + remotePath + ": " + e.getMessage(), e);
            }
            // 广度遍历，防止深层目录递归过深
            List<String> pending = new ArrayList<>();
            pending.add(normalized);
            while (!pending.isEmpty()) {
                String current = pending.remove(pending.size() - 1);
                for (ChannelSftp.LsEntry entry : listStrict(current)) {
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    String child = current.endsWith("/") ? current + name : current + "/" + name;
                    SftpATTRS entryAttrs = entry.getAttrs();
                    if (entryAttrs != null && entryAttrs.isDir()) {
                        pending.add(child);
                    } else {
                        fileCount++;
                        totalSize += Math.max(0, entryAttrs == null ? 0 : entryAttrs.getSize());
                    }
                }
            }
            return new Stats(fileCount, totalSize);
        }

        /**
         * 列目录，供删除使用。空目录或无权限时返回空列表——删除场景下
         * {@code removeAll} 会退化为直接 rmdir，列不出来反而是安全的。
         */
        @NotNull
        private List<ChannelSftp.LsEntry> listOrEmpty(@NotNull String remoteDirectory) {
            try {
                return channel.ls(remoteDirectory);
            } catch (SftpException e) {
                LOG.debug("列远端目录失败 " + remoteDirectory, e);
                return List.of();
            }
        }

        @Override
        @NotNull
        public List<String> listDirectory(@NotNull String remotePath) {
            String normalized = PathUtil.normalizeRemote(remotePath);
            if (normalized.isEmpty() || "/".equals(normalized)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (ChannelSftp.LsEntry entry : listOrEmpty(normalized)) {
                String name = entry.getFilename();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    continue;
                }
                names.add(name);
            }
            return names;
        }

        /**
         * 列目录，供完整性校验使用。此处必须严格：列不出目录意味着无法确认
         * 上传完整性，静默当成空目录会让比对失去意义。
         */
        @NotNull
        private List<ChannelSftp.LsEntry> listStrict(@NotNull String remoteDirectory) {
            try {
                return channel.ls(remoteDirectory);
            } catch (SftpException e) {
                throw new SshException("列远端目录失败 " + remoteDirectory + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void cd(@NotNull String remotePath) {
            String normalized = PathUtil.normalizeRemote(remotePath);
            mkdirs(normalized);
            try {
                channel.cd(normalized);
            } catch (SftpException e) {
                throw new SshException("切换远端目录失败 " + remotePath + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            if (channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * 每次读取前检查取消信号的输入流包装。
     *
     * <p>JSch 的 {@code ChannelSftp.put(InputStream, ...)} 会持续调用 {@code read} 直到
     * 返回 {@code -1}，在读取点插检查就能把「取消」变成一次正常的异常传播，
     * 从而中断正在传输的大文件——这是唯一能做到单文件级响应的位置。</p>
     */
    private static final class CancellingInputStream extends java.io.FilterInputStream {

        private final DeployCancellation cancellation;

        private CancellingInputStream(@NotNull InputStream in, @NotNull DeployCancellation cancellation) {
            super(in);
            this.cancellation = cancellation;
        }

        @Override
        public int read() throws java.io.IOException {
            checkCancelled();
            return super.read();
        }

        @Override
        public int read(@NotNull byte[] b, int off, int len) throws java.io.IOException {
            // SFTP 每次读几 KB 到几十 KB，粒度足够细，不会让用户感到迟钝
            checkCancelled();
            return super.read(b, off, len);
        }

        @Override
        public long skip(long n) throws java.io.IOException {
            checkCancelled();
            return super.skip(n);
        }

        private void checkCancelled() {
            if (cancellation.isCancelled()) {
                // 抛出即可中断 JSch 的读取循环。注意这里不能指望调用方按类型识别：
                // ChannelSftp.put 会把它包成 SftpException，所以调用方改用信号判断
                throw new DeployCancelledException(DeployStage.UPLOAD, "上传已取消");
            }
        }
    }
}
