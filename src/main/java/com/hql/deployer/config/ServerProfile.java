package com.hql.deployer.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一台服务器的连接配置。密码不保存在此处，由 {@link PasswordStore} 托管。
 *
 * @author hql on 2026/9/28
 */
public class ServerProfile implements Serializable, Cloneable {

    private static final long serialVersionUID = 7392615471583210964L;

    private String id = UUID.randomUUID().toString();
    private String name = "";
    /** 主机 IP 或域名，多个时每行一个 */
    private String hostList = "";
    private int port = 22;
    private String username = "root";
    /** 远端根目录，用于把相对的目标目录拼成绝对路径 */
    private String remoteBaseDir = "";
    private int connectTimeoutMs = 10_000;
    private int execTimeoutMs = 60_000;
    private int uploadTimeoutMs = 300_000;

    /**
     * 历史上部署备份的保留份数。
     *
     * <p>每次生效都会把线上目录改名成备份，部署频繁时这些备份会不断堆积，每个都含
     * 完整产物，几十次就能吃掉几个 G。设为 {@code 0} 表示不自动清理。</p>
     */
    private int backupKeepCount = 3;

    public ServerProfile() {
    }

    public ServerProfile(@NotNull String name, @NotNull String host, int port, @NotNull String username) {
        this.name = name;
        this.hostList = host;
        this.port = port;
        this.username = username;
    }

    @NotNull
    public String getId() {
        return id;
    }

    public void setId(@NotNull String id) {
        this.id = id;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public String getHostList() {
        return hostList;
    }

    public void setHostList(@NotNull String hostList) {
        this.hostList = hostList;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @NotNull
    public String getUsername() {
        return username;
    }

    public void setUsername(@NotNull String username) {
        this.username = username;
    }

    @NotNull
    public String getRemoteBaseDir() {
        return remoteBaseDir;
    }

    public void setRemoteBaseDir(@NotNull String remoteBaseDir) {
        this.remoteBaseDir = remoteBaseDir;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getExecTimeoutMs() {
        return execTimeoutMs;
    }

    public void setExecTimeoutMs(int execTimeoutMs) {
        this.execTimeoutMs = execTimeoutMs;
    }

    public int getUploadTimeoutMs() {
        return uploadTimeoutMs;
    }

    public void setUploadTimeoutMs(int uploadTimeoutMs) {
        this.uploadTimeoutMs = uploadTimeoutMs;
    }

    /**
     * 历史备份保留份数，{@code 0} 表示不自动清理。上限做钳制，避免误填成超大值把备份一次删光。
     */
    public int getBackupKeepCount() {
        return backupKeepCount;
    }

    public void setBackupKeepCount(int backupKeepCount) {
        this.backupKeepCount = Math.min(Math.max(backupKeepCount, 0), 20);
    }

    /**
     * 解析主机列表，去掉空行与首尾空白，保持书写顺序。
     */
    @NotNull
    public List<String> getHosts() {
        List<String> hosts = new ArrayList<>();
        for (String line : hostList.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return hosts;
    }

    /**
     * 取第一个可用主机。多主机场景下按配置顺序依次尝试连接。
     */
    @Nullable
    public String getFirstHost() {
        List<String> hosts = getHosts();
        return hosts.isEmpty() ? null : hosts.get(0);
    }

    /**
     * 校验必填项是否齐全，返回第一条错误描述，全部合法时返回 {@code null}。
     */
    @Nullable
    public String validate() {
        if (name.trim().isEmpty()) {
            return "服务器名称不能为空";
        }
        if (getHosts().isEmpty()) {
            return "主机列表不能为空";
        }
        if (username.trim().isEmpty()) {
            return "用户名不能为空";
        }
        if (port <= 0 || port > 65535) {
            return "端口不合法: " + port;
        }
        return null;
    }

    @NotNull
    public String getDisplayName() {
        List<String> hosts = getHosts();
        String host = hosts.isEmpty() ? "<未配置>" : hosts.get(0);
        return name + " (" + username + "@" + host + ":" + port + ")";
    }

    @Override
    public ServerProfile clone() {
        try {
            ServerProfile copy = (ServerProfile) super.clone();
            copy.id = this.id;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerProfile that)) {
            return false;
        }
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    @NotNull
    public String toString() {
        return getDisplayName();
    }
}
