package com.hql.deployer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排除规则匹配测试。
 *
 * @author hql on 2026/9/28
 */
class ExcludeMatcherTest {

    @Test
    @DisplayName("空规则不排除任何内容")
    void emptyRulesExcludeNothing() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of());

        assertTrue(matcher.isEmpty());
        assertFalse(matcher.isExcluded("a.txt"));
        assertFalse(matcher.isExcluded(".git/config"));
    }

    @Test
    @DisplayName("null 规则列表不排除任何内容")
    void nullRulesExcludeNothing() {
        assertFalse(ExcludeMatcher.isExcluded("a.txt", null));
    }

    @Test
    @DisplayName("纯名称命中任意层级")
    void simpleNameMatchesAnyLevel() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of(".git", "node_modules"));

        assertTrue(matcher.isExcluded(".git"));
        assertTrue(matcher.isExcluded(".git/config"));
        assertTrue(matcher.isExcluded("web/node_modules/pkg/index.js"));
        assertFalse(matcher.isExcluded("web/src/index.js"));
    }

    @Test
    @DisplayName("纯名称只做整段匹配，不做前缀匹配")
    void simpleNameDoesNotMatchPrefix() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("target"));

        assertTrue(matcher.isExcluded("target/app.jar"));
        assertFalse(matcher.isExcluded("target-old/app.jar"));
    }

    @Test
    @DisplayName("目录规则连同其下内容一起排除")
    void directoryRuleExcludesSubtree() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("logs/"));

        assertTrue(matcher.isExcluded("logs"));
        assertTrue(matcher.isExcluded("logs/app.log"));
        assertTrue(matcher.isExcluded("logs/2026/09/app.log"));
        assertFalse(matcher.isExcluded("app.log"));
    }

    @Test
    @DisplayName("目录规则不误伤别处的同名目录")
    void directoryRuleIsScoped() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("web/static/"));

        assertTrue(matcher.isExcluded("web/static/app.js"));
        assertFalse(matcher.isExcluded("app/src/static/app.js"));
    }

    @Test
    @DisplayName("glob 通配文件名")
    void globFileName() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("*.log"));

        assertTrue(matcher.isExcluded("app.log"));
        assertTrue(matcher.isExcluded("logs/nested/app.log"));
        assertFalse(matcher.isExcluded("app.txt"));
    }

    @Test
    @DisplayName("? 匹配单个字符")
    void globSingleChar() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("temp?.txt"));

        assertTrue(matcher.isExcluded("temp1.txt"));
        assertFalse(matcher.isExcluded("temp12.txt"));
    }

    @Test
    @DisplayName("** 跨层级匹配")
    void globRecursive() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("logs/**"));

        assertTrue(matcher.isExcluded("logs/app.log"));
        assertTrue(matcher.isExcluded("logs/2026/09/app.log"));
        assertFalse(matcher.isExcluded("app.log"));
    }

    @Test
    @DisplayName("点号按字面量处理")
    void dotIsLiteral() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("app.log"));

        assertTrue(matcher.isExcluded("app.log"));
        assertFalse(matcher.isExcluded("appXlog"));
    }

    @Test
    @DisplayName("输入路径归一化：反斜杠与前导 ./ /")
    void normalizesInputPath() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of(".git"));

        assertTrue(matcher.isExcluded(".\\.git\\config"));
        assertTrue(matcher.isExcluded("./.git/config"));
        assertTrue(matcher.isExcluded("/.git/config"));
    }

    @Test
    @DisplayName("空路径不排除")
    void emptyPathNotExcluded() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of(".git"));

        assertFalse(matcher.isExcluded(""));
        assertFalse(matcher.isExcluded("  "));
        assertFalse(matcher.isExcluded(null));
    }

    @Test
    @DisplayName("一行内用逗号或分号写多条规则")
    void splitsInlineRules() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of(".git, node_modules", "*.log"));

        assertTrue(matcher.isExcluded(".git/config"));
        assertTrue(matcher.isExcluded("node_modules/a.js"));
        assertTrue(matcher.isExcluded("app.log"));
    }

    @Test
    @DisplayName("# 开头视为注释")
    void ignoresComments() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of("# 这是注释", ".git"));

        assertTrue(matcher.isExcluded(".git/config"));
        assertFalse(matcher.isExcluded("这是注释"));
    }

    @Test
    @DisplayName("getRules 回显去掉空白与注释的规则")
    void exposesNormalizedRules() {
        ExcludeMatcher matcher = ExcludeMatcher.of(List.of(" .git , node_modules "));

        assertEquals(List.of(".git", "node_modules"), matcher.getRules());
    }

    @Test
    @DisplayName("joinRules 与 splitRules 互逆")
    void joinAndSplitRoundTrip() {
        List<String> rules = List.of(".git", "node_modules", "*.log");

        assertEquals(rules, ExcludeMatcher.splitRules(ExcludeMatcher.joinRules(rules)));
        assertEquals(null, ExcludeMatcher.joinRules(List.of()));
    }
}
