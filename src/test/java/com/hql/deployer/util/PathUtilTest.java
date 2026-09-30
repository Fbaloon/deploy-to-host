package com.hql.deployer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径工具测试。
 *
 * @author hql on 2026/9/28
 */
class PathUtilTest {

    @Nested
    @DisplayName("normalizeRemote")
    class NormalizeRemote {

        @Test
        @DisplayName("统一分隔符并合并重复斜杠")
        void mergesSeparators() {
            assertEquals("/opt/app", PathUtil.normalizeRemote("/opt//app"));
            assertEquals("/opt/app", PathUtil.normalizeRemote("\\opt\\app"));
        }

        @Test
        @DisplayName("解析 . 与 ..")
        void resolvesDotSegments() {
            assertEquals("/opt/app", PathUtil.normalizeRemote("/opt/./app"));
            assertEquals("/opt/app", PathUtil.normalizeRemote("/opt/tmp/../app"));
        }

        @Test
        @DisplayName("结果不带结尾斜杠，根目录除外")
        void noTrailingSlash() {
            assertEquals("/opt/app", PathUtil.normalizeRemote("/opt/app/"));
            assertEquals("/", PathUtil.normalizeRemote("/"));
        }

        @Test
        @DisplayName("相对路径中的 .. 保留，无法越过根")
        void keepsRelativeParentSegments() {
            assertEquals("../sibling", PathUtil.normalizeRemote("../sibling"));
            assertEquals("/", PathUtil.normalizeRemote("/../.."));
        }
    }

    @Nested
    @DisplayName("joinRemote")
    class JoinRemote {

        @Test
        @DisplayName("相对子路径拼到基础目录后")
        void joinsRelative() {
            assertEquals("/srv/www/app", PathUtil.joinRemote("/srv/www", "app"));
        }

        @Test
        @DisplayName("绝对子路径直接返回")
        void absoluteChildWins() {
            assertEquals("/data/app", PathUtil.joinRemote("/srv/www", "/data/app"));
        }

        @Test
        @DisplayName("基础目录为根时不会产生双斜杠")
        void rootBase() {
            assertEquals("/app", PathUtil.joinRemote("/", "app"));
        }
    }

    @Nested
    @DisplayName("relativeTo")
    class RelativeTo {

        @Test
        @DisplayName("子目录返回相对路径")
        void childPath() {
            File root = new File("C:/project");
            assertEquals("app", PathUtil.relativeTo(root, new File("C:/project/app")));
        }

        @Test
        @DisplayName("自身返回空串")
        void samePath() {
            File root = new File("C:/project");
            assertEquals("", PathUtil.relativeTo(root, new File("C:/project")));
        }

        @Test
        @DisplayName("外部路径返回 null")
        void outsidePath() {
            File root = new File("C:/project");
            assertNull(PathUtil.relativeTo(root, new File("C:/other/app")));
        }

        @Test
        @DisplayName("同名前缀不算子目录")
        void siblingWithSamePrefix() {
            File root = new File("C:/project");
            assertNull(PathUtil.relativeTo(root, new File("C:/project-other/app")));
        }
    }

    @Nested
    @DisplayName("shellQuote")
    class ShellQuote {

        @Test
        @DisplayName("普通路径被单引号包裹")
        void quotesPlainPath() {
            assertEquals("'/opt/app'", PathUtil.shellQuote("/opt/app"));
        }

        @Test
        @DisplayName("单引号按 POSIX 规则转义")
        void escapesSingleQuote() {
            assertEquals("'a'\\''b'", PathUtil.shellQuote("a'b"));
        }
    }

    @Nested
    @DisplayName("stagingName / backupName")
    class TempNames {

        @Test
        @DisplayName("暂存与备份名保留原目录并追加标记")
        void appendsMarker() {
            assertEquals("/opt/app.__staging_20260928", PathUtil.stagingName("/opt/app", "20260928"));
            assertEquals("/opt/app.__bak_20260928", PathUtil.backupName("/opt/app", "20260928"));
        }
    }

    @Nested
    @DisplayName("collectStats")
    class CollectStats {

        @Test
        @DisplayName("统计文件数与字节数并应用排除规则")
        void countsAndExcludes() throws Exception {
            Path root = Files.createTempDirectory("deployer-stats");
            try {
                Files.writeString(root.resolve("a.txt"), "12345");
                Files.writeString(root.resolve("b.log"), "1234567890");
                Files.createDirectory(root.resolve("sub"));
                Files.writeString(root.resolve("sub/c.txt"), "12");

                PathUtil.FileStats stats =
                        PathUtil.collectStats(root.toFile(), List.of("*.log"));

                assertEquals(2, stats.fileCount());
                assertEquals(7L, stats.totalBytes());
            } finally {
                deleteRecursively(root.toFile());
            }
        }

        @Test
        @DisplayName("单文件按 1 个文件统计")
        void singleFile() throws Exception {
            Path file = Files.createTempFile("deployer-single", ".txt");
            try {
                Files.writeString(file, "abcd");
                PathUtil.FileStats stats = PathUtil.collectStats(file.toFile(), List.of());

                assertEquals(1, stats.fileCount());
                assertEquals(4L, stats.totalBytes());
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        @DisplayName("不存在的路径统计为 0")
        void missingPath() {
            PathUtil.FileStats stats =
                    PathUtil.collectStats(new File("C:/not-exists-xyz"), List.of());

            assertEquals(0, stats.fileCount());
            assertEquals(0L, stats.totalBytes());
        }
    }

    @Nested
    @DisplayName("toPath")
    class ToPath {

        @Test
        @DisplayName("空白与 null 返回 null")
        void blankInput() {
            assertNull(PathUtil.toPath(null));
            assertNull(PathUtil.toPath("   "));
        }

        @Test
        @DisplayName("正常路径可解析")
        void validInput() {
            assertTrue(PathUtil.toPath("C:/opt/app") != null);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Test
    @DisplayName("resolve 空路径返回项目根")
    void resolveEmptyReturnsRoot() {
        File root = new File("C:/project");
        assertEquals(new File("C:/project").getAbsolutePath(),
                PathUtil.resolve(root, "  ").getAbsolutePath());
    }

    @Test
    @DisplayName("remoteFileName 提取最后一段")
    void remoteFileNameExtracts() {
        assertEquals("app.jar", PathUtil.remoteFileName("/opt/app/app.jar"));
        // 尾斜杠会被归一化去掉，因此该方法只取路径最后一段，不判断是否为目录
        assertEquals("app", PathUtil.remoteFileName("/opt/app/"));
        assertNull(PathUtil.remoteFileName("/"));
        assertNull(PathUtil.remoteFileName(""));
    }

    @Nested
    @DisplayName("remoteParent")
    class RemoteParent {

        @Test
        @DisplayName("取父目录")
        void returnsParent() {
            assertEquals("/data/service", PathUtil.remoteParent("/data/service/agentStore"));
        }

        @Test
        @DisplayName("根目录的父目录仍是根")
        void rootIsItsOwnParent() {
            assertEquals("/", PathUtil.remoteParent("/"));
        }

        @Test
        @DisplayName("顶层目录的父目录是根")
        void topLevelParentIsRoot() {
            assertEquals("/", PathUtil.remoteParent("/app"));
        }

        @Test
        @DisplayName("归一化尾斜杠后仍取到父目录")
        void normalizesTrailingSlash() {
            assertEquals("/data", PathUtil.remoteParent("/data/service/"));
        }
    }

    @Nested
    @DisplayName("selectExpiredBackups")
    class SelectExpiredBackups {

        private static final List<String> SIBLINGS = List.of(
                "app", "app.conf", "app-data", "app.log",
                "app.__staging_20260929142520",
                "app.__bak_20260101010101",
                "app.__bak_20260202020202",
                "app.__bak_20260303030303",
                "app.__bak_20260404040404",
                "app.__bak_backup", "app.__bak_2026010101010", "app.__bak_202601010101011");

        @Test
        @DisplayName("保留最新的 N 份，其余按时间倒序返回")
        void keepsNewestAndReturnsRest() {
            List<String> expired = PathUtil.selectExpiredBackups(SIBLINGS, "/srv/app", 2);

            assertEquals(List.of("app.__bak_20260202020202", "app.__bak_20260101010101"), expired);
        }

        @Test
        @DisplayName("备份不足 N 份时一个都不删")
        void keepsAllWhenUnderLimit() {
            List<String> siblings = List.of("app.__bak_20260101010101", "app.__bak_20260202020202");

            assertTrue(PathUtil.selectExpiredBackups(siblings, "/srv/app", 2).isEmpty());
            assertTrue(PathUtil.selectExpiredBackups(siblings, "/srv/app", 5).isEmpty());
        }

        @Test
        @DisplayName("只认本目标目录的备份，同级的 app-data 不受影响")
        void ignoresOtherDirectories() {
            List<String> expired = PathUtil.selectExpiredBackups(SIBLINGS, "/srv/app", 1);

            assertFalse(expired.contains("app-data"));
            assertFalse(expired.contains("app.conf"));
            assertFalse(expired.contains("app.log"));
            for (String name : expired) {
                assertTrue(name.startsWith("app.__bak_"), name);
            }
        }

        @Test
        @DisplayName("暂存目录与命名不合规的条目都不会被删")
        void ignoresStagingAndMalformedNames() {
            List<String> expired = PathUtil.selectExpiredBackups(SIBLINGS, "/srv/app", 1);

            assertFalse(expired.contains("app.__staging_20260929142520"), "暂存目录不能当备份删掉");
            assertFalse(expired.contains("app.__bak_backup"), "时间戳缺失，不是备份");
            assertFalse(expired.contains("app.__bak_2026010101010"), "13 位不是 14 位时间戳");
            assertFalse(expired.contains("app.__bak_202601010101011"), "15 位不是 14 位时间戳");
        }

        @Test
        @DisplayName("保留数为 0 表示不清理")
        void zeroMeansKeepEverything() {
            assertTrue(PathUtil.selectExpiredBackups(SIBLINGS, "/srv/app", 0).isEmpty());
            assertTrue(PathUtil.selectExpiredBackups(SIBLINGS, "/srv/app", -1).isEmpty());
        }

        @Test
        @DisplayName("目标目录名含正则元字符时按字面量匹配")
        void treatsDotInNameAsLiteral() {
            List<String> siblings = List.of(
                    "my.app.__bak_20260101010101",
                    "myXapp.__bak_20260101010101");

            assertTrue(PathUtil.selectExpiredBackups(siblings, "/srv/my.app", 1).isEmpty(),
                    "未转义的 . 会把 myXapp 误认成 my.app 的备份而删掉别人的目录");
        }

        @Test
        @DisplayName("目标目录带尾斜杠也能正确匹配")
        void handlesTrailingSlash() {
            List<String> siblings = List.of(
                    "app.__bak_20260101010101",
                    "app.__bak_20260202020202");

            assertEquals(List.of("app.__bak_20260101010101"),
                    PathUtil.selectExpiredBackups(siblings, "/srv/app/", 1));
        }

        @Test
        @DisplayName("无任何备份时返回空列表")
        void returnsEmptyWhenNoBackups() {
            assertTrue(PathUtil.selectExpiredBackups(List.of("app", "app.log"), "/srv/app", 3).isEmpty());
            assertTrue(PathUtil.selectExpiredBackups(List.of(), "/srv/app", 3).isEmpty());
        }
    }

    @Nested
    @DisplayName("ServerProfile 的备份保留份数")
    class BackupKeepCount {

        @Test
        @DisplayName("新服务器默认保留 3 份")
        void defaultsToThree() {
            assertEquals(3, new com.hql.deployer.config.ServerProfile().getBackupKeepCount(),
                    "默认必须自动清理，否则老用户升级后会继续堆积");
        }

        @Test
        @DisplayName("超出范围的输入被钳制")
        void clampsOutOfRange() {
            com.hql.deployer.config.ServerProfile profile = new com.hql.deployer.config.ServerProfile();
            profile.setBackupKeepCount(-5);
            assertEquals(0, profile.getBackupKeepCount());
            profile.setBackupKeepCount(9999);
            assertEquals(20, profile.getBackupKeepCount(), "误填超大值会一次删光备份，必须钳制");
        }

        @Test
        @DisplayName("克隆会带上保留份数")
        void cloneCopiesKeepCount() {
            com.hql.deployer.config.ServerProfile profile = new com.hql.deployer.config.ServerProfile();
            profile.setBackupKeepCount(7);

            assertEquals(7, profile.clone().getBackupKeepCount(),
                    "克隆逐字段复制，新字段必须自动跟随，否则设置页一点「应用」就丢配置");
        }
    }
}
