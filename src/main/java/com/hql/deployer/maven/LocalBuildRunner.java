package com.hql.deployer.maven;

import com.hql.deployer.core.DeployCancellation;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessAdapter;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本地构建命令执行器，用于执行 Maven、Gradle、npm 等任意构建命令。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>超时后由 {@code runProcess(timeout, destroyOnTimeout)} 主动销毁进程，返回值带
 *       {@code isTimeout} 标记，调用方据此立即失败</li>
 *   <li>输出既累积到 {@link ProcessOutput}（用于失败时展示尾部日志），也通过回调实时推给 UI</li>
 *   <li>退出码非 0 立即失败，配合流水线实现快速失败</li>
 * </ul>
 *
 * @author hql on 2026/9/28
 */
public final class LocalBuildRunner {

    /**
     * 轮询间隔：既决定取消/超时的响应速度，也决定 CPU 空转程度，100ms 足够灵敏。
     */
    private static final long POLL_INTERVAL_MS = 100L;

    /**
     * 发出销毁后等待进程真正退出的上限，超时即放弃，避免异常情况下永久阻塞。
     */
    private static final long DESTROY_WAIT_MS = 5_000L;

    /** 等待 {@code taskkill} 收尾的上限 */
    private static final long TREE_KILL_WAIT_MS = 5_000L;

    private LocalBuildRunner() {
    }

    /**
     * 命令执行结果。
     *
     * @param exitCode  进程退出码
     * @param stdout    标准输出
     * @param stderr    标准错误
     * @param timedOut  是否因超时被终止
     * @param cancelled 是否被外部取消
     */
    public record Result(int exitCode,
                         @NotNull String stdout,
                         @NotNull String stderr,
                         boolean timedOut,
                         boolean cancelled) {

        public boolean isSuccess() {
            return !timedOut && !cancelled && exitCode == 0;
        }

        /**
         * 供用户阅读的失败原因。
         */
        @NotNull
        public String describeFailure() {
            if (timedOut) {
                return "命令执行超时已被终止";
            }
            if (cancelled) {
                return "命令已被取消";
            }
            return "命令退出码非 0: " + exitCode;
        }
    }

    /**
     * 执行命令并等待结束。
     *
     * @param command        命令与参数
     * @param workingDir     工作目录，为 {@code null} 时使用项目根
     * @param timeoutMs      超时时间（毫秒），小于等于 0 表示不限制
     * @param envVars        追加的环境变量，可为 {@code null}
     * @param outputListener 实时输出回调，可为 {@code null}
     * @throws ExecutionException 进程构造失败（命令不存在等）时抛出
     */
    @NotNull
    public static Result run(@NotNull List<String> command,
                             @Nullable File workingDir,
                             int timeoutMs,
                             @Nullable Map<String, String> envVars,
                             @Nullable OutputListener outputListener) throws ExecutionException {
        return run(command, workingDir, timeoutMs, envVars, outputListener, null);
    }

    /**
     * 执行命令并等待结束，支持外部中断。
     *
     * <p>取消时立即销毁子进程并返回 {@code cancelled=true}，不等它自然结束——
     * Maven 打包动辄几十秒，让「停止」按钮形同虚设是这类工具最常见的体验缺陷。</p>
     *
     * @param command        命令与参数
     * @param workingDir     工作目录，为 {@code null} 时使用项目根
     * @param timeoutMs      超时时间（毫秒），小于等于 0 表示不限制
     * @param envVars        追加的环境变量，可为 {@code null}
     * @param outputListener 实时输出回调，可为 {@code null}
     * @param cancellation   取消信号，可为 {@code null}（不响应取消）
     * @throws ExecutionException 进程构造失败（命令不存在等）时抛出
     */
    @NotNull
    public static Result run(@NotNull List<String> command,
                             @Nullable File workingDir,
                             int timeoutMs,
                             @Nullable Map<String, String> envVars,
                             @Nullable OutputListener outputListener,
                             @Nullable DeployCancellation cancellation) throws ExecutionException {
        if (command.isEmpty()) {
            throw new ExecutionException("构建命令为空");
        }
        GeneralCommandLine commandLine = new GeneralCommandLine(command);
        commandLine.setCharset(Charset.defaultCharset());
        if (workingDir != null) {
            commandLine.setWorkDirectory(workingDir);
        }
        if (envVars != null) {
            envVars.forEach(commandLine::withEnvironment);
        }

        ForwardingHandler handler = new ForwardingHandler(commandLine, outputListener);
        // 取消与超时都走同一个等待循环。不能图省事在无取消时改用 runProcess(timeout, true)：
        // 它会先杀掉根进程，我们随后再 taskkill 就找不到这棵树了，孙进程会活下来继续跑。
        return waitForResult(handler, timeoutMs, cancellation);
    }

    /**
     * 等待进程结束，同时盯住取消信号与截止时间。
     *
     * <p>{@link CapturingProcessHandler#waitFor(long)} 返回是否已结束，因此可以按
     * 固定间隔轮询两个外部条件，任一命中即强杀进程树。</p>
     */
    @NotNull
    private static Result waitForResult(@NotNull ForwardingHandler handler,
                                        int timeoutMs,
                                        @Nullable DeployCancellation cancellation) {
        handler.startNotify();
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;

        boolean timedOut = false;
        boolean cancelled = false;
        while (!handler.waitFor(POLL_INTERVAL_MS)) {
            if (cancellation != null && cancellation.isCancelled()) {
                cancelled = true;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
                break;
            }
        }
        if (cancelled || timedOut) {
            // 顺序很关键：必须先按 PID 强杀整棵树，再 destroyProcess()。
            // Windows 上命令被包成 cmd.exe /c ...，cmd.exe 一死，
            // taskkill 就找不到这棵树了，孙进程（Maven 的 java.exe）会活下来继续编译，
            // 表现为「点了停止但构建还在跑」。
            killProcessTree(handler);
            handler.destroyProcess();
            // 等待被杀干净，避免僵尸进程继续占用文件句柄
            handler.waitFor(DESTROY_WAIT_MS);
        }
        return toResult(handler.requireProcessOutput(), timedOut, cancelled);
    }

    /**
     * 按 PID 强杀整棵进程树。
     *
     * <p>只在 Windows 上做：Unix 的 {@code Process.destroy} 本来就会带走整组进程。
     * 拿不到 PID、进程已退出或 {@code taskkill} 不可用时静默跳过——这是兜底手段，
     * 不该因为杀不掉而把用户的「取消」变成新的失败。</p>
     */
    private static void killProcessTree(@NotNull ProcessHandler handler) {
        if (!SystemInfo.isWindows) {
            return;
        }
        long pid = nativePidOrZero(handler);
        if (pid <= 0) {
            return;
        }
        try {
            Process killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                    .redirectErrorStream(true)
                    .start();
            killer.waitFor(TREE_KILL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // taskkill 不可用或进程已退出：忽略，destroyProcess 仍会收尾
        }
    }

    private static long nativePidOrZero(@NotNull ProcessHandler handler) {
        try {
            Long pid = handler.getNativePid().get(1, TimeUnit.SECONDS);
            return pid == null ? 0L : pid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0L;
        } catch (Exception e) {
            // 进程尚未启动或已退出，取不到 PID 属正常
            return 0L;
        }
    }

    @NotNull
    private static Result toResult(@NotNull ProcessOutput output, boolean timedOut, boolean cancelled) {
        return new Result(
                output.getExitCode(),
                nullSafe(output.getStdout()),
                nullSafe(output.getStderr()),
                timedOut,
                cancelled);
    }

    /**
     * 在累积输出的同时把文本实时转发的处理器。
     *
     * <p>2026.2 的 {@link CapturingProcessHandler} 不再暴露 {@code getProcessOutput()}，
     * 所以这里在 {@link #createProcessAdapter} 回调里把输出对象存下来：那是唯一能拿到
     * 它、且保证在 {@code startNotify} 之前完成的时机。</p>
     */
    private static final class ForwardingHandler extends CapturingProcessHandler {

        private final OutputListener listener;

        /** 由 {@link #createProcessAdapter} 在 {@code startNotify()} 期间填充 */
        private volatile ProcessOutput processOutput;

        private ForwardingHandler(@NotNull GeneralCommandLine commandLine,
                                  @Nullable OutputListener listener) throws ExecutionException {
            super(commandLine);
            this.listener = listener;
            // Windows 上命令被包成 cmd.exe /c mvn.cmd，真正干活的 java.exe 是孙进程。
            // 只杀 cmd.exe 的话 Maven 会继续跑并占着 target 目录，
            // 用户点了「停止」却发现构建还在继续，比不响应停止更糟。
            setShouldDestroyProcessRecursively(true);
        }

        @NotNull
        private ProcessOutput requireProcessOutput() {
            ProcessOutput output = processOutput;
            if (output == null) {
                // 走到这里说明 startNotify 没跑成功，进程根本没起来
                return new ProcessOutput();
            }
            return output;
        }

        @Override
        protected CapturingProcessAdapter createProcessAdapter(@NotNull ProcessOutput output) {
            processOutput = output;
            return new CapturingProcessAdapter(output) {
                @Override
                public void onTextAvailable(@NotNull com.intellij.execution.process.ProcessEvent event,
                                            @NotNull com.intellij.openapi.util.Key outputType) {
                    super.onTextAvailable(event, outputType);
                    if (listener == null) {
                        return;
                    }
                    String text = event.getText();
                    if (text != null && !text.isEmpty()) {
                        listener.onOutput(text);
                    }
                }
            };
        }
    }

    @NotNull
    private static String nullSafe(@Nullable String value) {
        return value == null ? "" : value;
    }

    /**
     * 探测构建命令是否可执行，用于预检阶段提前失败。
     *
     * <p>Windows 上 {@code mvn} 实际是 {@code mvn.cmd}，直接执行会因扩展名问题失败，
     * 因此按 {@code PATHEXT} 逐个尝试补全扩展名。</p>
     */
    @Nullable
    public static String resolveExecutable(@NotNull String command) {
        if (command.isBlank()) {
            return null;
        }
        String raw = command.trim();
        String resolved = FileUtil.toSystemDependentName(raw);

        // 含路径分隔符：先补全 Windows 上省略的脚本后缀，再按路径直接判断
        if (resolved.indexOf('/') >= 0 || resolved.indexOf(File.separatorChar) >= 0) {
            if (SystemInfo.isWindows && !hasScriptExtension(resolved) && FileUtil.exists(resolved + ".cmd")) {
                return resolved + ".cmd";
            }
            if (SystemInfo.isWindows && !hasScriptExtension(resolved) && FileUtil.exists(resolved + ".bat")) {
                return resolved + ".bat";
            }
            return FileUtil.exists(resolved) ? resolved : null;
        }

        String pathValue = System.getenv("PATH");
        if (pathValue == null) {
            return null;
        }
        String pathExt = System.getenv("PATHEXT");
        if (pathExt == null) {
            pathExt = ".COM;.EXE;.BAT;.CMD";
        }
        String[] directories = pathValue.split(File.pathSeparator);

        List<String> candidates = new ArrayList<>();
        candidates.add(raw);
        for (String ext : pathExt.split(";")) {
            String trimmed = ext.trim();
            if (!trimmed.isEmpty()) {
                candidates.add(raw + trimmed.toLowerCase());
            }
        }

        for (String directory : directories) {
            if (directory.trim().isEmpty()) {
                continue;
            }
            File match = findExecutableIn(directory.trim(), raw, candidates);
            if (match != null) {
                return match.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * 在单个目录内按优先级找可执行文件。
     *
     * <p>Windows 上 Maven 的 {@code bin} 目录同时存在无扩展名的 {@code mvn}（sh 脚本）和
     * {@code mvn.cmd}。{@code ProcessBuilder} 无法执行无扩展名文件，因此这里把
     * {@code .cmd}/{@code .bat}/{@code .exe} 排在裸名之前，避免选中必然启动失败的那个。</p>
     */
    @Nullable
    private static File findExecutableIn(@NotNull String directory,
                                         @NotNull String raw,
                                         @NotNull List<String> candidates) {
        if (!SystemInfo.isWindows) {
            for (String candidate : candidates) {
                File file = new File(directory, candidate);
                if (file.isFile() && file.canExecute()) {
                    return file;
                }
            }
            return null;
        }
        // 第一轮：只处理无后缀的裸名，按优先级补全为可被 ProcessBuilder 启动的类型
        for (String ext : new String[]{".cmd", ".bat", ".exe", ".com"}) {
            File file = new File(directory, raw + ext);
            if (file.isFile()) {
                return file;
            }
        }
        // 第二轮：候选里已带后缀的直接判断
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(raw)) {
                continue;
            }
            File file = new File(directory, candidate);
            if (file.isFile()) {
                return file;
            }
        }
        // 第三轮：兜底裸名（仅当平台确实允许执行无扩展名文件时才会命中）
        for (String candidate : candidates) {
            File file = new File(directory, candidate);
            if (file.isFile() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    /**
     * 定位 Maven 可执行文件的绝对路径。
     *
     * <p>探测顺序：用户显式配置 → {@code MAVEN_HOME} → {@code M2_HOME} → PATH。
     * Windows 上返回 {@code mvn.cmd} 这样的脚本路径，调用方仍需经
     * {@link #prepareCommandForExecution} 包装成 {@code cmd /c} 才能启动。</p>
     */
    @Nullable
    public static String resolveMavenExecutable(@Nullable String configured) {
        if (configured != null && !configured.isBlank()) {
            return resolveExecutable(configured);
        }
        for (String envName : new String[]{"MAVEN_HOME", "M2_HOME"}) {
            String home = System.getenv(envName);
            if (home == null || home.isBlank()) {
                continue;
            }
            File candidate = new File(FileUtil.toSystemDependentName(home.trim()),
                    "bin" + File.separator + (SystemInfo.isWindows ? "mvn.cmd" : "mvn"));
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return resolveExecutable("mvn");
    }

    /**
     * Windows 上 {@code .cmd}/{@code .bat} 不能被 {@link ProcessBuilder} 直接启动
     * （{@code CreateProcess error=2 / error=193}），必须交给 {@code cmd.exe /c}。
     * 这里对命令首项判定脚本扩展名并补上包装。
     */
    @NotNull
    public static List<String> prepareCommandForExecution(@NotNull List<String> command) {
        if (command.isEmpty() || !SystemInfo.isWindows) {
            return command;
        }
        String exe = command.get(0).toLowerCase();
        if (exe.endsWith(".cmd") || exe.endsWith(".bat")) {
            List<String> wrapped = new ArrayList<>(command.size() + 2);
            wrapped.add("cmd.exe");
            wrapped.add("/c");
            wrapped.addAll(command);
            return wrapped;
        }
        return command;
    }

    /**
     * Windows 上 {@code mvn} 需补成 {@code mvn.cmd} 才能被 {@link ProcessBuilder} 执行，
     * 这里对命令首项做同样的补全，避免用户每次都要写全名。
     */
    /**
     * Windows 上把 {@code mvn} 补成带后缀的可执行文件，并给出可直接启动的绝对路径。
     *
     * <p>裸名（如 {@code mvn}）在 Windows 上有三种可能：PATH 里的无扩展名 sh 脚本（不可执行）、
     * {@code mvn.cmd}、{@code mvn.exe}。这里统一交由 {@link #resolveExecutable} 判定，
     * 找不到时原样返回，保留用户显式指定的绝对路径。</p>
     */
    @NotNull
    public static String normalizeFirstToken(@NotNull String token) {
        if (!SystemInfo.isWindows) {
            return token;
        }
        if (token.contains("/") || token.contains("\\") || hasScriptExtension(token)) {
            return token;
        }
        String resolved = resolveExecutable(token);
        return resolved == null ? token : resolved;
    }

    private static boolean hasScriptExtension(@NotNull String token) {
        String name = token.toLowerCase();
        return name.endsWith(".cmd") || name.endsWith(".bat")
                || name.endsWith(".exe") || name.endsWith(".com");
    }

    /**
     * 按空白与引号切分命令行，支持 {@code "path with space"} 形式。
     */
    @NotNull
    public static List<String> tokenize(@NotNull String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuotes = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        if (!tokens.isEmpty()) {
            tokens.set(0, normalizeFirstToken(tokens.get(0)));
        }
        return tokens;
    }

    /**
     * 输出回调。
     */
    @FunctionalInterface
    public interface OutputListener {
        void onOutput(@NotNull String text);
    }
}
