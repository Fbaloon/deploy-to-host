package com.hql.deployer.util;

import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 上传排除规则匹配。
 *
 * <p>支持三种写法：</p>
 * <ul>
 *   <li><b>纯名称</b>：{@code .git}、{@code node_modules}、{@code target}
 *       —— 命中任意层级的同名文件或目录，写法最简，推荐优先使用</li>
 *   <li><b>目录规则</b>：{@code logs/}、{@code web/}
 *       —— 命中该目录及其下全部内容，不含目录自身</li>
 *   <li><b>Glob</b>：{@code *.log}、{@code temp?.txt}、{@code a/b/c.txt}、{@code logs/**}{@code /*}
 *       —— 按路径或文件名的 glob 规则匹配</li>
 * </ul>
 *
 * <p>路径统一使用 {@code /} 分隔、相对上传根目录。命中任一祖先目录即整棵子树被排除。</p>
 *
 * @author hql on 2026/9/28
 */
public final class ExcludeMatcher {

    /**
     * 单条规则。分三类以避免用复杂正则同时表达全部语义。
     *
     * @param raw         原始文本
     * @param simpleName  纯名称规则的值，非空时生效
     * @param pathPattern 匹配完整相对路径的 glob
     * @param dirPattern  匹配目录名的 glob（用于祖先目录判定）
     * @param namePattern 匹配文件名的 glob，用于 {@code *.log} 这类"任意层级文件名"写法
     */
    private record Rule(@NotNull String raw,
                         @Nullable String simpleName,
                         @Nullable Pattern pathPattern,
                         @Nullable Pattern dirPattern,
                         @Nullable Pattern namePattern) {

        static Rule of(@NotNull String raw) {
            String normalized = raw.replace('\\', '/');
            while (normalized.length() > 1 && normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (normalized.isEmpty()) {
                return new Rule(raw, "", null, null, null);
            }
            boolean hasWildcard = normalized.indexOf('*') >= 0 || normalized.indexOf('?') >= 0;
            if (!hasWildcard && normalized.indexOf('/') < 0) {
                return new Rule(raw, normalized, null, null, null);
            }
            Pattern path = Pattern.compile(globToRegex(normalized) + "(?:/.*)?");
            // 祖先目录匹配：把末尾的 ** 或文件名段裁掉，只保留目录前缀
            String dirPart = normalized.contains("/")
                    ? normalized.substring(0, normalized.lastIndexOf('/'))
                    : normalized;
            Pattern dir = dirPart.isEmpty() ? null : Pattern.compile(globToRegex(dirPart));
            // 不含 '/' 的通配写法（如 *.log）应命中任意层级的文件名
            Pattern name = normalized.indexOf('/') < 0 ? Pattern.compile(globToRegex(normalized)) : null;
            return new Rule(raw, null, path, dir, name);
        }
    }


    private final List<Rule> rules;
    private final List<String> rawRules;

    private ExcludeMatcher(@NotNull List<Rule> rules, @NotNull List<String> rawRules) {
        this.rules = rules;
        this.rawRules = rawRules;
    }

    @NotNull
    public static ExcludeMatcher of(@Nullable List<String> inputRules) {
        List<Rule> parsed = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        if (inputRules != null) {
            for (String rawRule : inputRules) {
                if (rawRule == null) {
                    continue;
                }
                // 允许用户用逗号、分号或换行在一行里写多条规则
                for (String part : rawRule.split("[,;\\r\\n]")) {
                    String rule = part.trim();
                    if (rule.isEmpty() || rule.startsWith("#")) {
                        continue;
                    }
                    raw.add(rule);
                    parsed.add(Rule.of(rule));
                }
            }
        }
        return new ExcludeMatcher(parsed, raw);
    }

    @NotNull
    public List<String> getRules() {
        return rawRules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * 判断相对路径（相对上传根，使用 {@code /} 分隔）是否应被排除。
     */
    public boolean isExcluded(@Nullable String relativePath) {
        if (rules.isEmpty() || relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            return false;
        }

        String[] segments = normalized.split("/");
        String path = String.join("/", segments);

        for (Rule rule : rules) {
            // 1) 纯名称：任意层级的同名文件或目录
            if (rule.simpleName() != null) {
                for (String segment : segments) {
                    if (segment.equals(rule.simpleName())) {
                        return true;
                    }
                }
                continue;
            }
            // 2) glob：命中完整路径
            if (rule.pathPattern() != null && rule.pathPattern().matcher(path).matches()) {
                return true;
            }
            // 3) glob：命中最后一段文件名（*.log 匹配任意层级的 .log）
            if (rule.namePattern() != null && rule.namePattern().matcher(segments[segments.length - 1]).matches()) {
                return true;
            }
            // 4) glob：命中任一祖先目录，则整棵子树排除
            if (rule.dirPattern() != null) {
                for (int i = 0; i < segments.length - 1; i++) {
                    String ancestor = String.join("/", java.util.Arrays.copyOfRange(segments, 0, i + 1));
                    if (rule.dirPattern().matcher(ancestor).matches()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * glob 转正则。仅支持 {@code *}、{@code ?}、{@code **}，其余字符按字面量处理。
     */
    @NotNull
    private static String globToRegex(@NotNull String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i += 2;
                        if (i < glob.length() && glob.charAt(i) == '/') {
                            // a/**/b 同时匹配 a/b
                            i++;
                            regex.append("(?:[^/]+/)*");
                        }
                    } else {
                        regex.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    regex.append("[^/]");
                    i++;
                }
                case '.' -> {
                    regex.append("\\.");
                    i++;
                }
                default -> {
                    if ("\\^$+()[]{}|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                    i++;
                }
            }
        }
        return regex.toString();
    }

    /**
     * 静态快捷方法。
     */
    public static boolean isExcluded(@NotNull String relativePath, @Nullable List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        return of(rules).isExcluded(relativePath);
    }

    /**
     * 展示用：规则列表拼成一行文本。
     */
    @Nullable
    public static String joinRules(@Nullable List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        return String.join(", ", rules);
    }

    /**
     * 解析展示文本为规则列表，按逗号、分号或换行分隔。
     */
    @NotNull
    public static List<String> splitRules(@Nullable String text) {
        List<String> rules = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rules;
        }
        for (String part : text.split("[,;\\r\\n]")) {
            String rule = part.trim();
            if (!rule.isEmpty()) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /**
     * 判断 {@code candidate} 是否位于 {@code root} 之内（含相等）。
     */
    public static boolean isInside(@NotNull File root, @NotNull File candidate) {
        String rootPath = FileUtil.toCanonicalPath(root.getAbsolutePath());
        String candidatePath = FileUtil.toCanonicalPath(candidate.getAbsolutePath());
        // toCanonicalPath 输出系统无关风格，分隔符固定为 '/'，不能用 File.separator
        String prefix = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        return candidatePath.equals(rootPath) || candidatePath.startsWith(prefix);
    }
}
