package com.hql.deployer.maven;

import com.hql.deployer.core.DeployCancellation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地构建命令解析测试。
 *
 * <p>覆盖「构建命令启动失败: Cannot run program mvn (CreateProcess error=2)」这一线上问题的根因：
 * Windows 上 {@code mvn} 是 {@code mvn.cmd} 脚本，{@code ProcessBuilder} 无法直接启动。</p>
 *
 * @author hql on 2026/9/28
 */
class LocalBuildRunnerTest {

    @Nested
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 脚本命令")
    class WindowsScript {

        @Test
        @DisplayName("resolveExecutable 对带路径但省略 .cmd 后缀的 mvn 自动补全")
        void completesMissingCmdSuffix(@TempDir File tempDir) throws Exception {
            File bin = new File(tempDir, "bin");
            assertTrue(bin.mkdirs());
            File mvn = new File(bin, "mvn.cmd");
            assertTrue(mvn.createNewFile());

            String configured = new File(bin, "mvn").getAbsolutePath();
            String resolved = LocalBuildRunner.resolveExecutable(configured);

            assertNotNull(resolved, "省略 .cmd 后缀时应当补全");
            assertTrue(resolved.endsWith("mvn.cmd"), "实际解析为: " + resolved);
        }

        @Test
        @DisplayName("prepareCommandForExecution 用 cmd /c 包装 .cmd 命令")
        void wrapsCmdScriptWithCmdExe(@TempDir File tempDir) throws Exception {
            File mvn = new File(tempDir, "mvn.cmd");
            assertTrue(mvn.createNewFile());

            List<String> wrapped = LocalBuildRunner.prepareCommandForExecution(
                    List.of(mvn.getAbsolutePath(), "clean", "package"));

            assertEquals("cmd.exe", wrapped.get(0));
            assertEquals("/c", wrapped.get(1));
            assertEquals(mvn.getAbsolutePath(), wrapped.get(2));
            assertEquals("clean", wrapped.get(3));
            assertEquals("package", wrapped.get(4));
        }

        @Test
        @DisplayName("非脚本命令不被包装")
        void leavesExeUntouched() {
            List<String> command = List.of("C:\\tools\\maven\\bin\\mvn.exe", "-v");

            List<String> result = LocalBuildRunner.prepareCommandForExecution(command);

            assertSame(command, result);
        }

        @Test
        @DisplayName("tokenize 对 PATH 中的 mvn 补全 .cmd 后缀")
        void tokenizeCompletesExtension() {
            String expected = LocalBuildRunner.resolveExecutable("mvn");
            if (expected == null) {
                // 测试机 PATH 中没有 mvn，无法验证补全
                return;
            }
            assertTrue(expected.toLowerCase().endsWith(".cmd"),
                    "前提：PATH 中的 mvn 解析为 .cmd，实际: " + expected);

            List<String> tokens = LocalBuildRunner.tokenize("mvn clean package");

            assertEquals(expected, tokens.get(0));
            assertEquals(List.of(tokens.get(0), "clean", "package"), tokens);
        }
    }

    @Nested
    @DisplayName("Maven 定位")
    class MavenResolution {

        @Test
        @DisplayName("显式配置优先，路径不存在时返回 null")
        void respectsConfiguredPath(@TempDir File tempDir) throws Exception {
            File bin = new File(tempDir, "bin");
            assertTrue(bin.mkdirs());
            File mvn = new File(bin, "mvn.cmd");
            assertTrue(mvn.createNewFile());

            assertEquals(mvn.getAbsolutePath(),
                    LocalBuildRunner.resolveMavenExecutable(mvn.getAbsolutePath()));

            assertNull(LocalBuildRunner.resolveMavenExecutable(
                    new File(new File(tempDir, "not-exist"), "mvn").getAbsolutePath()));
        }

        @Test
        @DisplayName("配置为空时回退到 MAVEN_HOME 或 PATH")
        void fallsBackWhenUnconfigured() {
            String resolved = LocalBuildRunner.resolveMavenExecutable(null);

            if (resolved == null) {
                // 测试机既无 MAVEN_HOME 也无 PATH 中的 mvn，属于合理环境
                return;
            }
            assertTrue(new File(resolved).isFile(), "解析结果必须是真实文件: " + resolved);
        }

        @Test
        @DisplayName("空白配置等同于未配置")
        void blankFallsBack() {
            assertEquals(LocalBuildRunner.resolveMavenExecutable(null),
                    LocalBuildRunner.resolveMavenExecutable("   "));
        }
    }

    @Nested
    @DisplayName("tokenize")
    class Tokenize {

        @Test
        @DisplayName("保留引号内的空格")
        void keepsQuotedSpaces() {
            List<String> tokens = LocalBuildRunner.tokenize(
                    "java -Dname=\"a b\" -jar \"C:\\Program Files\\app.jar\"");

            // 首项会解析为可执行的绝对路径（Windows 上可能是 java.exe），这里只校验分词结果
            assertTrue(tokens.get(0).toLowerCase().contains("java"),
                    "首项应为 java 可执行文件，实际: " + tokens.get(0));
            assertEquals("-Dname=a b", tokens.get(1));
            assertEquals("-jar", tokens.get(2));
            assertEquals("C:\\Program Files\\app.jar", tokens.get(3));
        }

        @Test
        @DisplayName("连续空白折叠，空命令返回空列表")
        void collapsesWhitespace() {
            assertEquals(2, LocalBuildRunner.tokenize("a    b").size());
            assertTrue(LocalBuildRunner.tokenize("   ").isEmpty());
        }
    }

    @Nested
    @DisplayName("临时目录契约")
    class TempDirSanity {

        @Test
        @DisplayName("@TempDir 确实可写")
        void tempDirIsUsable(@TempDir File tempDir) throws Exception {
            Files.writeString(new File(tempDir, "a.txt").toPath(), "x");
            assertTrue(new File(tempDir, "a.txt").isFile());
        }
    }

    @Nested
    @DisplayName("构建取消")
    class Cancellation {

        /**
         * 跑满 30 秒的命令：只有进程真被杀掉，等待才会提前结束。
         *
         * <p>不设工作目录：被杀的孙进程会持有工作目录句柄，若把工作目录放在
         * {@code @TempDir} 里，JUnit 清理临时目录时会因文件被占用而失败。</p>
         */
        @Test
        @DisplayName("取消长任务：立即返回 cancelled，不等它自然结束")
        void cancelsLongRunningProcess() throws Exception {
            DeployCancellation cancellation = new DeployCancellation();

            long start = System.currentTimeMillis();
            Thread canceller = new Thread(() -> {
                sleepQuietly(800);
                cancellation.cancel();
            });
            canceller.start();
            LocalBuildRunner.Result result =
                    LocalBuildRunner.run(longSleepCommand(), null, 0, null, null, cancellation);
            canceller.join();
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result.cancelled(), "结果应标记为取消: " + result);
            assertTrue(elapsed < 25_000, "取消后应立即返回，实际耗时 " + elapsed
                    + " ms，说明等待循环没有响应取消信号");
        }

        @Test
        @DisplayName("未取消时正常运行到结束，不误报取消")
        void completesNormallyWithoutCancellation() throws Exception {
            LocalBuildRunner.Result result =
                    LocalBuildRunner.run(shortCommand(), null, 0, null, null, new DeployCancellation());

            assertTrue(result.isSuccess(), result.toString());
            assertTrue(!result.cancelled(), "不应误报取消");
        }

        @Test
        @DisplayName("超时后结束且不标记为取消")
        void timeoutIsNotCancellation() throws Exception {
            LocalBuildRunner.Result result =
                    LocalBuildRunner.run(longSleepCommand(), null, 1_000, null, null, null);

            assertTrue(result.timedOut(), "应报告超时: " + result);
            assertTrue(!result.cancelled(), "超时不等于用户取消");
        }
    }

    /**
     * 跨平台的长耗时命令（30 秒）。
     */
    @NotNull
    private static List<String> longSleepCommand() {
        if (com.intellij.openapi.util.SystemInfo.isWindows) {
            return List.of("cmd.exe", "/c", "ping -n 31 127.0.0.1 > nul");
        }
        return List.of("/bin/sh", "-c", "sleep 30");
    }

    @NotNull
    private static List<String> shortCommand() {
        if (com.intellij.openapi.util.SystemInfo.isWindows) {
            return List.of("cmd.exe", "/c", "echo ok");
        }
        return List.of("/bin/sh", "-c", "echo ok");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    @DisplayName("构建 JDK 路径校验")
    class JdkHome {

        @Test
        @DisplayName("含 bin/java 的目录被接受")
        void acceptsJdkRoot(@TempDir File tempDir) throws Exception {
            File bin = new File(tempDir, "bin");
            assertTrue(bin.mkdirs());
            File java = new File(bin, com.intellij.openapi.util.SystemInfo.isWindows ? "java.exe" : "java");
            assertTrue(java.createNewFile());

            assertEquals(tempDir.getAbsolutePath(), BuildJdkResolver.jdkHomeOf(tempDir.getAbsolutePath()));
        }

        @Test
        @DisplayName("指向 jre 子目录时上提到 JDK 根")
        void liftsJreSubdirectory(@TempDir File tempDir) throws Exception {
            File bin = new File(tempDir, "bin");
            assertTrue(bin.mkdirs());
            File java = new File(bin, com.intellij.openapi.util.SystemInfo.isWindows ? "java.exe" : "java");
            assertTrue(java.createNewFile());
            File jre = new File(tempDir, "jre");
            assertTrue(jre.mkdirs());

            assertEquals(tempDir.getAbsolutePath(), BuildJdkResolver.jdkHomeOf(jre.getAbsolutePath()));
        }

        @Test
        @DisplayName("缺少 bin/java 的目录被拒绝")
        void rejectsNonJdkDirectory(@TempDir File tempDir) {
            assertNull(BuildJdkResolver.jdkHomeOf(tempDir.getAbsolutePath()));
        }

        @Test
        @DisplayName("null、空串与不存在的路径均返回 null")
        void rejectsInvalidInput(@TempDir File tempDir) {
            assertNull(BuildJdkResolver.jdkHomeOf(null));
            assertNull(BuildJdkResolver.jdkHomeOf("   "));
            assertNull(BuildJdkResolver.jdkHomeOf(new File(tempDir, "missing").getAbsolutePath()));
        }
    }
}
