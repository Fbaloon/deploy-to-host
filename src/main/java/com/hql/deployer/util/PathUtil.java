package com.hql.deployer.util;

import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 路径与远端 POSIX 路径处理工具。
 *
 * @author hql on 2026/9/28
 */
public final class PathUtil {

    private PathUtil() {
    }

    /**
     * 将配置中的路径解析为绝对路径。
     *
     * <p>规则：空串解析为项目根；绝对路径原样返回；其余按项目根拼接。</p>
     */
    @NotNull
    public static File resolve(@NotNull File projectRoot, @NotNull String path) {
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return projectRoot;
        }
        File file = new File(trimmed);
        if (file.isAbsolute()) {
            return new File(FileUtil.toCanonicalPath(file.getAbsolutePath()));
        }
        return new File(FileUtil.toCanonicalPath(new File(projectRoot, trimmed).getAbsolutePath()));
    }

    /**
     * 归一化 POSIX 远端路径：统一分隔符为 {@code /}、合并重复斜杠、解析 {@code .} 与 {@code ..}。
     *
     * <p>结果不以斜杠结尾（根目录 {@code /} 除外）。</p>
     */
    @NotNull
    public static String normalizeRemote(@NotNull String path) {
        String unified = path.trim().replace('\\', '/');
        boolean absolute = unified.startsWith("/");
        String[] parts = unified.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.get(stack.size() - 1))) {
                    stack.remove(stack.size() - 1);
                } else if (!absolute) {
                    stack.add("..");
                }
                continue;
            }
            stack.add(part);
        }
        String joined = String.join("/", stack);
        if (absolute) {
            return "/" + joined;
        }
        return joined;
    }

    /**
     * 在远端基础目录上拼接子路径。{@code child} 为绝对路径时直接返回它。
     */
    @NotNull
    public static String joinRemote(@NotNull String baseDir, @NotNull String child) {
        String normalizedChild = normalizeRemote(child);
        String normalizedBase = normalizeRemote(baseDir);
        if (normalizedChild.startsWith("/")) {
            return normalizedChild;
        }
        if (normalizedBase.isEmpty()) {
            return normalizedChild;
        }
        if (normalizedBase.equals("/")) {
            return "/" + normalizedChild;
        }
        return normalizedBase + "/" + normalizedChild;
    }

    /**
     * 把绝对路径转成相对 {@code root} 的相对路径，用于「模块路径」这类配置项的回显。
     *
     * @return 相对路径；{@code target} 不在 {@code root} 之下时返回 {@code null}
     */
    @Nullable
    public static String relativeTo(@NotNull File root, @NotNull File target) {
        try {
            String rootPath = FileUtil.toCanonicalPath(root.getAbsolutePath());
            String targetPath = FileUtil.toCanonicalPath(target.getAbsolutePath());
            if (targetPath.equals(rootPath)) {
                return "";
            }
            // toCanonicalPath 输出的是系统无关风格（Windows 上也是 '/'），
            // 这里必须用 '/' 拼前缀，用 File.separator 会在 Windows 上永远匹配不上
            String prefix = rootPath.endsWith("/") ? rootPath : rootPath + "/";
            return targetPath.startsWith(prefix) ? targetPath.substring(prefix.length()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 取远端路径的文件名，目录或空串返回 {@code null}。
     */
    @Nullable
    public static String remoteFileName(@NotNull String remotePath) {
        String normalized = normalizeRemote(remotePath);
        if (normalized.isEmpty() || normalized.endsWith("/")) {
            return null;
        }
        int index = normalized.lastIndexOf('/');
        return index >= 0 && index < normalized.length() - 1 ? normalized.substring(index + 1) : null;
    }

    /**
     * 单引号包裹远端路径，供远端 shell 命令引用。
     */
    @NotNull
    public static String shellQuote(@NotNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * 递归统计目录下的文件数量与总字节数，被排除规则命中的文件不计入。
     *
     * <p>用于上传前后与远端做数量/大小比对，尽早发现静默截断。</p>
     */
    public record FileStats(int fileCount, long totalBytes) {
    }

    @NotNull
    public static FileStats collectStats(@NotNull File root,
                                         @NotNull List<String> excludes) {
        if (root.isFile()) {
            return new FileStats(1, root.length());
        }
        if (!root.isDirectory()) {
            return new FileStats(0, 0L);
        }
        int[] count = {0};
        long[] bytes = {0L};
        try {
            Files.walk(root.toPath())
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        Path relative = root.toPath().relativize(path);
                        String relativePath = relative.toString().replace('\\', '/');
                        if (!ExcludeMatcher.isExcluded(relativePath, excludes)) {
                            count[0]++;
                            try {
                                bytes[0] += Files.size(path);
                            } catch (IOException ignored) {
                                // 统计失败不阻断流程
                            }
                        }
                    });
        } catch (IOException e) {
            return new FileStats(count[0], bytes[0]);
        }
        return new FileStats(count[0], bytes[0]);
    }

    /**
     * 保留扩展名的临时目录名：{@code <name>.__staging_<timestamp>}。
     */
    @NotNull
    public static String stagingName(@NotNull String targetDirectory, @NotNull String suffix) {
        String normalized = normalizeRemote(targetDirectory);
        return normalized + ".__staging_" + suffix;
    }

    /**
     * 保留扩展名的备份目录名：{@code <name>.__bak_<timestamp>}。
     */
    @NotNull
    public static String backupName(@NotNull String targetDirectory, @NotNull String suffix) {
        String normalized = normalizeRemote(targetDirectory);
        return normalized + ".__bak_" + suffix;
    }

    /**
     * 备份目录名的严格匹配模式。
     *
     * <p>时间戳固定 14 位（{@code yyyyMMddHHmmss}），因此字符串字典序即时序，
     * 可以直接按名字排序而不必关心文件系统返回的顺序。</p>
     */
    private static final String BACKUP_NAME_REGEX = "__bak_(\\d{14})";

    /**
     * 从目标目录的同级条目中挑出应当删除的历史备份名。
     *
     * <p>只认严格符合 {@code <目标目录名>.__bak_<14位时间戳>} 的条目：其它名字一律跳过。
     * 目录名用 {@link java.util.regex.Pattern#quote} 转义，否则像 {@code my.app} 这样的
     * 目标目录会让正则里的 {@code .} 变成通配符，有误删同级无关目录的风险。</p>
     *
     * <p>只挑选、不删除——删除动作交给调用方，以便在流水线里统一处理失败。</p>
     *
     * @param siblingNames    目标目录父目录下的条目名
     * @param targetDirectory 目标目录
     * @param keep            保留份数，{@code <= 0} 表示不清理
     * @return 需要删除的条目名，按时间倒序（最新在前）
     */
    @NotNull
    public static List<String> selectExpiredBackups(@NotNull List<String> siblingNames,
                                                    @NotNull String targetDirectory,
                                                    int keep) {
        if (keep <= 0 || siblingNames.isEmpty()) {
            return List.of();
        }
        String normalized = normalizeRemote(targetDirectory);
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash < 0) {
            return List.of();
        }
        String baseName = normalized.substring(lastSlash + 1);
        if (baseName.isEmpty()) {
            return List.of();
        }
        Pattern pattern = Pattern.compile("^" + Pattern.quote(baseName) + "\\." + BACKUP_NAME_REGEX + "$");

        List<String> backups = new ArrayList<>();
        for (String name : siblingNames) {
            if (pattern.matcher(name).matches()) {
                backups.add(name);
            }
        }
        if (backups.size() <= keep) {
            return List.of();
        }
        // 固定宽度的数字后缀，按字典序倒排即时间倒序，保留最新的 keep 份
        backups.sort(Comparator.reverseOrder());
        return List.copyOf(backups.subList(keep, backups.size()));
    }

    /**
     * 取远端路径的父目录，根目录返回 {@code /}。
     */
    @NotNull
    public static String remoteParent(@NotNull String remotePath) {
        String normalized = normalizeRemote(remotePath);
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return normalized.substring(0, lastSlash);
    }

    /**
     * 解析 Path 字符串，失败时返回 {@code null}。
     */
    @Nullable
    public static Path toPath(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Paths.get(path);
        } catch (Exception e) {
            return null;
        }
    }
}
