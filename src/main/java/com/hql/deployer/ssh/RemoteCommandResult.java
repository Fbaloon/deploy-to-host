package com.hql.deployer.ssh;

import org.jetbrains.annotations.NotNull;

/**
 * 单条远端命令的执行结果。
 *
 * @param exitCode 远端退出码
 * @param stdout   标准输出
 * @param stderr   标准错误
 * @author hql on 2026/9/28
 */
public record RemoteCommandResult(int exitCode, @NotNull String stdout, @NotNull String stderr) {

    /**
     * 退出码为 0 视为成功。SSH 断连等异常已在 {@link SshSession#exec} 侧转为异常。
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }

    /**
     * 合并标准输出与标准错误，便于在日志里一次性展示。
     */
    @NotNull
    public String getCombinedOutput() {
        if (stdout.isEmpty()) {
            return stderr;
        }
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout + "\n" + stderr;
    }
}
