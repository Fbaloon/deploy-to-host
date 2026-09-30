package com.hql.deployer.config;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务器密码的读写封装，密文交由 IDE 的 PasswordSafe 管理，明文不落盘。
 *
 * @author hql on 2026/9/28
 */
public final class PasswordStore {

    private static final Logger LOG = Logger.getInstance(PasswordStore.class);

    private static final String SUBSYSTEM = "MavenQuickDeploy";

    private PasswordStore() {
    }

    @NotNull
    private static CredentialAttributes attributes(@NotNull String hostId) {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(SUBSYSTEM, "host-" + hostId));
    }

    /**
     * 构造只含密码、不含用户名的凭据。
     *
     * <p>这里必须用 {@code (userName, char[])} 构造器：{@code Credentials(String)} 的实际签名是
     * {@code Credentials(userName, password = null)}，传单个字符串会被当成 userName，密码字段保持
     * {@code null}，PasswordSafe 会「保存成功」但条目里根本没有密码，且不会抛任何异常。</p>
     */
    @NotNull
    private static Credentials credentials(@NotNull String password) {
        return new Credentials(null, password.toCharArray());
    }

    /**
     * 读取密码，未保存时返回空字符串而非 {@code null}，方便直接填入输入框。
     *
     * <p>取值失败（IDE 关闭、PasswordSafe 被 KeePass 主密码锁定等）时记 {@code warn} 日志。
     * 这里不做异常抛出：调用方拿到空串会给出「未配置密码」的提示，比让整个设置页打不开更可控。</p>
     */
    @NotNull
    public static String getPassword(@NotNull String hostId) {
        if (hostId.isEmpty()) {
            return "";
        }
        if (isPasswordSafeUnavailable()) {
            return "";
        }
        CredentialAttributes attributes = attributes(hostId);
        try {
            String password = PasswordSafe.getInstance().getPassword(attributes);
            if (password == null) {
                LOG.info("PasswordSafe 中未找到服务器密码: " + hostId);
                return "";
            }
            return password;
        } catch (Throwable e) {
            LOG.warn("读取服务器密码失败 (" + hostId + ")，PasswordSafe 可能被锁定或不可用", e);
            return "";
        }
    }

    /**
     * 保存密码。传入 {@code null} 或空串表示清除已保存的密码。
     */
    public static void setPassword(@NotNull String hostId, @Nullable String password) {
        if (hostId.isEmpty()) {
            return;
        }
        if (isPasswordSafeUnavailable()) {
            return;
        }
        CredentialAttributes attributes = attributes(hostId);
        try {
            if (password == null || password.isEmpty()) {
                PasswordSafe.getInstance().set(attributes, null);
                LOG.info("清除服务器密码: " + hostId);
                return;
            }
            PasswordSafe.getInstance().set(attributes, credentials(password));
            // PasswordSafe 写失败时不会抛异常，这里立即回读确认，避免用户到部署时才发现密码没存上
            String back = PasswordSafe.getInstance().getPassword(attributes);
            if (!password.equals(back)) {
                LOG.warn("服务器密码写入后回读不一致 (" + hostId + ")，PasswordSafe 存储可能不可用");
            }
            LOG.info("已保存服务器密码: " + hostId);
        } catch (Throwable e) {
            // 密码存储不可用不应阻断配置保存，但必须留下痕迹，否则用户会在部署时才发现密码没存上
            LOG.warn("保存服务器密码失败 (" + hostId + ")，该服务器将无法部署", e);
        }
    }

    /**
     * 密码存储是否处于不可用状态。
     *
     * <p>没有 Application 上下文时（单元测试、独立的工具进程）{@code PasswordSafe.getInstance()}
     * 必然抛 NPE。这个 NPE 每次调用都会重复出现并带完整堆栈，把 CI 日志和部署日志刷满，
     * 却对定位任何问题毫无帮助——它只说明「当前不在 IDE 里」，没有更具体的信息。
     * 因此这里提前判定并只留一行说明，不制造噪音。</p>
     */
    private static boolean isPasswordSafeUnavailable() {
        if (ApplicationManager.getApplication() != null) {
            return false;
        }
        LOG.info("当前无 IDE Application 上下文，跳过 PasswordSafe 访问");
        return true;
    }

    /**
     * 复制密码到新主机（用于复制服务器配置）。
     */
    public static void copyPassword(@NotNull String fromHostId, @NotNull String toHostId) {
        setPassword(toHostId, getPassword(fromHostId));
    }
}
