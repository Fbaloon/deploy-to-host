package com.hql.deployer.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务器配置的全局存储（应用级）。密码不在此保存，见 {@link PasswordStore}。
 *
 * @author hql on 2026/9/28
 */
@State(name = "MavenQuickDeploySettings", storages = @Storage("MavenQuickDeploySettings.xml"))
public final class ServerProfileService implements PersistentStateComponent<ServerProfileService.State> {

    /**
     * 全局默认值，新建配置时作为初始值，避免每个配置重复填超时。
     */
    public static final class Defaults {
        /** Maven 可执行文件路径，空表示自动探测 */
        public String mavenExecutable = "";
        public int packageTimeoutMs = 600_000;
        public int connectTimeoutMs = 10_000;
        public int execTimeoutMs = 60_000;
        public boolean stagingEnabled = true;
        public boolean notifyEnabled = true;
    }

    public static final class State {
        public List<ServerProfile> servers = new ArrayList<>();
        @OptionTag("DEFAULT_MAVEN_EXECUTABLE")
        public String mavenExecutable = "";
        @OptionTag("DEFAULT_PACKAGE_TIMEOUT_MS")
        public int packageTimeoutMs = 600_000;
        @OptionTag("DEFAULT_CONNECT_TIMEOUT_MS")
        public int connectTimeoutMs = 10_000;
        @OptionTag("DEFAULT_EXEC_TIMEOUT_MS")
        public int execTimeoutMs = 60_000;
        @OptionTag("DEFAULT_STAGING_ENABLED")
        public boolean stagingEnabled = true;
        @OptionTag("DEFAULT_NOTIFY_ENABLED")
        public boolean notifyEnabled = true;
    }

    private State state = new State();

    @NotNull
    public static ServerProfileService getInstance() {
        return ApplicationManager.getApplication().getService(ServerProfileService.class);
    }

    @Override
    @Nullable
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
    }

    @NotNull
    public List<ServerProfile> getServers() {
        return state.servers;
    }

    @NotNull
    public Optional<ServerProfile> findById(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return state.servers.stream().filter(s -> s.getId().equals(id)).findFirst();
    }

    /**
     * 查找服务器，找不到时返回用于告警提示的占位对象而不是 {@code null}，避免调用方到处判空。
     */
    @NotNull
    public ServerProfile findOrPlaceholder(@Nullable String id) {
        return findById(id).orElseGet(() -> {
            ServerProfile placeholder = new ServerProfile();
            placeholder.setId(id == null ? "" : id);
            placeholder.setName("<未找到服务器>");
            return placeholder;
        });
    }

    @NotNull
    public ServerProfile addServer(@Nullable String name) {
        ServerProfile profile = new ServerProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(name == null || name.isEmpty() ? "新服务器" : name);
        profile.setConnectTimeoutMs(state.connectTimeoutMs);
        profile.setExecTimeoutMs(state.execTimeoutMs);
        state.servers.add(profile);
        return profile;
    }

    public void removeServer(@NotNull ServerProfile profile) {
        state.servers.removeIf(s -> s.getId().equals(profile.getId()));
        PasswordStore.setPassword(profile.getId(), null);
    }

    /**
     * 整体替换服务器列表，保留调用方给定的顺序。设置页保存时调用。
     *
     * <p>不在此删除密码：密码按主机 id 存放，删除操作由 {@link #removeServer} 单独负责，
     * 避免用户调整列表顺序时误清密码。</p>
     */
    public void replaceServers(@NotNull List<ServerProfile> servers) {
        state.servers.clear();
        state.servers.addAll(servers);
    }

    @NotNull
    public Defaults toDefaults() {
        Defaults defaults = new Defaults();
        defaults.mavenExecutable = state.mavenExecutable;
        defaults.packageTimeoutMs = state.packageTimeoutMs;
        defaults.connectTimeoutMs = state.connectTimeoutMs;
        defaults.execTimeoutMs = state.execTimeoutMs;
        defaults.stagingEnabled = state.stagingEnabled;
        defaults.notifyEnabled = state.notifyEnabled;
        return defaults;
    }
}
