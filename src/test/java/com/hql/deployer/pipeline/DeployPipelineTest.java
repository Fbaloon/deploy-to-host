package com.hql.deployer.pipeline;

import com.hql.deployer.config.ServerProfile;
import com.hql.deployer.core.DeployEvent;
import com.hql.deployer.core.DeployException;
import com.hql.deployer.core.DeployListener;
import com.hql.deployer.core.DeployResult;
import com.hql.deployer.core.DeployStage;
import com.hql.deployer.runconfig.BuildMode;
import com.hql.deployer.ssh.RemoteCommandResult;
import com.hql.deployer.ssh.SshSession;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布流水线测试。
 *
 * <p>重点覆盖三个对外承诺：仅打包绝不碰 SSH、激活失败必须还原原目录、
 * 上传失败必须清理暂存目录且不动线上目录。</p>
 *
 * @author hql on 2026/9/28
 */
class DeployPipelineTest {

    @TempDir
    Path projectRoot;

    @BeforeEach
    void writeArtifact() throws IOException {
        Files.writeString(projectRoot.resolve("app.jar"), "jar-content");
    }

    // ---------------------------------------------------------------- 成功路径

    @Test
    @DisplayName("上传并原子切换：先备份旧目录，再把暂存目录改名到目标目录")
    void deploySucceedsWithAtomicSwitch() {
        FakeSshSession session = new FakeSshSession();
        // 线上目录已存在，才能验证"先备份再切换"
        session.remoteDirectories.add("/srv/www/app");
        RecordingListener listener = new RecordingListener();

        DeployResult result = new DeployPipeline(session).execute(task(false), listener);

        assertTrue(result.success(), result.message());
        assertEquals(DeployStage.DONE, result.stage());

        // 两步都是 rename：先把线上目录改名备份，再把暂存目录改名到线上目录
        assertEquals(2, session.renameCalls.size(), session.renameCalls.toString());
        assertTrue(session.renameCalls.get(0).startsWith("/srv/www/app -> /srv/www/app.__bak_"),
                session.renameCalls.get(0));
        assertTrue(session.renameCalls.get(1).startsWith("/srv/www/app.__staging_"),
                session.renameCalls.get(1));
        assertTrue(session.renameCalls.get(1).endsWith(" -> /srv/www/app"), session.renameCalls.get(1));
        assertTrue(listener.stages.contains(DeployStage.PRECHECK));
        assertTrue(listener.stages.contains(DeployStage.UPLOAD));
        assertTrue(listener.stages.contains(DeployStage.ACTIVATE));
        assertTrue(result.backupDirectory() != null
                && result.backupDirectory().startsWith("/srv/www/app.__bak_"),
                "应报告备份目录以便手动回滚: " + result.backupDirectory());
    }

    @Test
    @DisplayName("目标目录原本不存在时不产生备份")
    void firstDeployCreatesNoBackup() {
        FakeSshSession session = new FakeSshSession();

        DeployResult result = new DeployPipeline(session).execute(task(false), new RecordingListener());

        assertTrue(result.success());
        assertNull(result.backupDirectory());
    }

    @Test
    @DisplayName("未配置部署后命令时结果阶段为 DONE")
    void successWithoutPostCommand() {
        FakeSshSession session = new FakeSshSession();

        DeployResult result = new DeployPipeline(session).execute(task(false), new RecordingListener());

        assertTrue(result.success());
        assertEquals("发布完成", result.message());
        assertEquals(0, session.execCalls.size());
    }

    @Test
    @DisplayName("配置了上传前命令时，命令先执行成功再继续上传")
    void preUploadCommandRunsBeforeUpload() {
        FakeSshSession session = new FakeSshSession();
        session.remoteDirectories.add("/srv/www/app");
        RecordingListener listener = new RecordingListener();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setPreUploadCommand("./prep.sh"), listener);

        assertTrue(result.success(), result.message());
        assertTrue(listener.stages.contains(DeployStage.PRE_COMMAND),
                "应进入上传前命令阶段: " + listener.stages);
        assertEquals(List.of("./prep.sh"), session.execCalls);
        // 命令成功后正常走上传与原子切换：备份 + 暂存切到目标
        assertEquals(2, session.renameCalls.size(), session.renameCalls.toString());
        assertTrue(session.renameCalls.stream().anyMatch(call -> call.endsWith("-> /srv/www/app")));
    }

    @Test
    @DisplayName("上传前命令成功且部署后命令也在用时按顺序执行两条命令")
    void preAndPostCommandsRunInOrder() {
        FakeSshSession session = new FakeSshSession();
        RecordingListener listener = new RecordingListener();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false)
                        .setPreUploadCommand("./prep.sh")
                        .setAfterDeployCommand("./restart.sh"), listener);

        assertTrue(result.success(), result.message());
        assertEquals(List.of("./prep.sh", "./restart.sh"), session.execCalls);
        assertEquals(DeployStage.DONE, result.stage());
    }

    @Test
    @DisplayName("未配置上传前命令时跳过该阶段")
    void noPreUploadCommandSkipsStage() {
        FakeSshSession session = new FakeSshSession();
        RecordingListener listener = new RecordingListener();

        DeployResult result = new DeployPipeline(session).execute(task(false), listener);

        assertTrue(result.success());
        assertFalse(listener.stages.contains(DeployStage.PRE_COMMAND),
                "未配置命令不应进入上传前命令阶段: " + listener.stages);
        assertTrue(session.execCalls.isEmpty());
    }

    @Test
    @DisplayName("上传前命令为空字符串时按未配置处理")
    void blankPreUploadCommandSkipsStage() {
        FakeSshSession session = new FakeSshSession();
        RecordingListener listener = new RecordingListener();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setPreUploadCommand("   "), listener);

        assertTrue(result.success(), result.message());
        assertFalse(listener.stages.contains(DeployStage.PRE_COMMAND));
        assertTrue(session.execCalls.isEmpty());
    }

    // ---------------------------------------------------------------- 仅打包

    @Test
    @DisplayName("仅打包模式完全不建立 SSH 连接")
    void packageOnlyNeverConnects() throws IOException {
        Files.writeString(projectRoot.resolve("build.sh"), "#!/bin/sh\n");
        FakeSshSession session = new FakeSshSession();

        DeployTask task = task(false)
                .setPackageOnly(true)
                .setBuildMode(BuildMode.UPLOAD_FILE);

        DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

        assertTrue(result.success(), result.message());
        assertEquals(DeployStage.BUILD, result.stage());
        assertFalse(session.connected, "仅打包不应连接 SSH");
        assertEquals(0, session.openSftpCount);
        assertTrue(session.renameCalls.isEmpty());
    }

    @Test
    @DisplayName("仅打包模式不校验服务器与远端目录")
    void packageOnlySkipsRemoteValidation() {
        ServerProfile incomplete = new ServerProfile();
        incomplete.setName("未填主机");
        FakeSshSession session = new FakeSshSession();

        DeployTask task = task(false)
                .setPackageOnly(true)
                .setBuildMode(BuildMode.UPLOAD_FILE)
                .setHost(incomplete)
                .setTargetDirectory("");

        DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

        assertTrue(result.success(), result.message());
        assertFalse(session.connected);
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    @DisplayName("上传前命令失败：立即终止，线上目录完全未动")
    void preUploadCommandFailureStopsImmediately() {
        FakeSshSession session = new FakeSshSession();
        session.execResults.add(new RemoteCommandResult(1, "", "prep failed"));
        RecordingListener listener = new RecordingListener();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setPreUploadCommand("./prep.sh"), listener);

        assertFalse(result.success());
        assertEquals(DeployStage.PRE_COMMAND, result.stage());
        assertTrue(result.message().contains("上传前命令退出码"), result.message());
        assertTrue(result.message().contains("线上目录未受影响"), result.message());
        // 上传尚未开始，绝不能有目录切换、写入或清理
        assertTrue(session.renameCalls.isEmpty(), "上传前命令失败不应发生切换: " + session.renameCalls);
        assertTrue(session.writtenFiles.isEmpty(), "上传前命令失败不应写入文件: " + session.writtenFiles);
        assertTrue(session.removed.isEmpty(), "上传前命令失败不应清理任何目录: " + session.removed);
    }

    @Test
    @DisplayName("上传前命令退出码 127 时提示命令找不到的排查方向")
    void preUploadCommandExit127ExplainsCommandNotFound() {
        FakeSshSession session = new FakeSshSession();
        session.execResults.add(new RemoteCommandResult(127, "", "prep.sh: command not found"));

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setPreUploadCommand("/data/scripts/prep.sh"), new RecordingListener());

        assertEquals(DeployStage.PRE_COMMAND, result.stage());
        assertTrue(result.message().contains("找不到该命令"), result.message());
        assertTrue(result.message().contains("非交互式 shell"), result.message());
        assertTrue(session.renameCalls.isEmpty());
    }

    @Test
    @DisplayName("上传失败：清理暂存目录，线上目录完全未动")
    void uploadFailureCleansStagingOnly() {
        FakeSshSession session = new FakeSshSession();
        session.failUpload = true;

        DeployResult result = new DeployPipeline(session).execute(task(false), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.UPLOAD, result.stage());
        // 线上目录没被 rename 过，且暂存目录已被清理
        assertTrue(session.renameCalls.isEmpty(), "上传失败不应发生任何切换: " + session.renameCalls);
        assertTrue(session.removed.stream().anyMatch(path -> path.contains(".__staging_")),
                "应清理暂存目录: " + session.removed);
    }

    @Test
    @DisplayName("切换失败：把备份改回原名，线上目录可用")
    void activateFailureRestoresBackup() {
        FakeSshSession session = new FakeSshSession();
        // 线上目录已存在，必须先产生备份才有"还原"可言
        session.remoteDirectories.add("/srv/www/app");
        session.failRenameWhenTargetIsFinalDirectory = true;

        DeployResult result = new DeployPipeline(session).execute(task(false), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.ACTIVATE, result.stage());
        // 备份 → 目标 的还原动作必须发生
        assertTrue(session.renameCalls.stream()
                        .anyMatch(call -> call.startsWith("/srv/www/app.__bak_")
                                && call.endsWith(" -> /srv/www/app")),
                "切换失败必须还原备份: " + session.renameCalls);
    }

    @Test
    @DisplayName("部署后命令失败：新文件保留，不自动回滚")
    void postCommandFailureKeepsNewFiles() {
        FakeSshSession session = new FakeSshSession();
        RemoteCommandResult failed = new RemoteCommandResult(1, "", "boom");
        session.execResults.add(failed);

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setAfterDeployCommand("./restart.sh"), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.POST_COMMAND, result.stage());
        assertTrue(result.message().contains("未重启"), result.message());
        // 目标目录已被切换，不做回滚
        assertTrue(session.renameCalls.stream().anyMatch(call -> call.endsWith("-> /srv/www/app")));
    }

    @Test
    @DisplayName("部署后命令退出码 127 时提示命令找不到的排查方向")
    void postCommandExit127ExplainsCommandNotFound() {
        FakeSshSession session = new FakeSshSession();
        session.execResults.add(new RemoteCommandResult(127, "", "run.sh: restart: command not found"));

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setAfterDeployCommand("/data/service/agentStore/run.sh restart"),
                        new RecordingListener());

        assertEquals(DeployStage.POST_COMMAND, result.stage());
        assertTrue(result.message().contains("找不到该命令"), result.message());
        assertTrue(result.message().contains("非交互式 shell"), result.message());
        assertTrue(result.message().contains("CRLF"), result.message());
    }

    @Test
    @DisplayName("部署后命令退出码 126 时提示缺少可执行权限")
    void postCommandExit126ExplainsPermissionDenied() {
        FakeSshSession session = new FakeSshSession();
        session.execResults.add(new RemoteCommandResult(126, "", "Permission denied"));

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setAfterDeployCommand("/data/service/agentStore/run.sh restart"),
                        new RecordingListener());

        assertEquals(DeployStage.POST_COMMAND, result.stage());
        assertTrue(result.message().contains("chmod +x"), result.message());
    }

    @Test
    @DisplayName("非 126/127 的退出码不追加无意义的排查提示")
    void otherExitCodesGetNoExtraHint() {
        FakeSshSession session = new FakeSshSession();
        session.execResults.add(new RemoteCommandResult(1, "", "boom"));

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setAfterDeployCommand("/data/service/agentStore/run.sh restart"),
                        new RecordingListener());

        assertTrue(result.message().contains("未重启"), result.message());
        assertFalse(result.message().contains("chmod +x"), result.message());
        assertFalse(result.message().contains("非交互式 shell"), result.message());
    }

    @Test
    @DisplayName("服务器配置不完整时预检阶段直接失败")
    void precheckFailsOnIncompleteServer() {
        ServerProfile incomplete = new ServerProfile();
        incomplete.setName("缺主机");
        FakeSshSession session = new FakeSshSession();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setHost(incomplete), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.PRECHECK, result.stage());
        assertFalse(session.connected);
    }

    @Test
    @DisplayName("目标目录为空时预检阶段失败")
    void precheckFailsOnEmptyTarget() {
        FakeSshSession session = new FakeSshSession();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setTargetDirectory("   "), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.PRECHECK, result.stage());
    }

    @Test
    @DisplayName("待上传路径不存在时构建阶段失败，不建立连接")
    void missingArtifactFailsBeforeConnect() {
        FakeSshSession session = new FakeSshSession();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setLocalPath("not-exist"), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.BUILD, result.stage());
        assertFalse(session.connected, "产物不存在时不应连接服务器");
    }

    @Test
    @DisplayName("空目录（全部被排除）视为产物缺失")
    void emptyDirectoryFails() throws IOException {
        Path emptyDir = Files.createDirectory(projectRoot.resolve("empty"));
        FakeSshSession session = new FakeSshSession();

        DeployResult result = new DeployPipeline(session)
                .execute(task(false).setLocalPath("empty"), new RecordingListener());

        assertFalse(result.success());
        assertEquals(DeployStage.BUILD, result.stage());
        assertTrue(emptyDir.toFile().isDirectory());
    }

    @Test
    @DisplayName("取消后立即失败且不建立连接")
    void cancelledTaskFailsFast() {
        FakeSshSession session = new FakeSshSession();
        DeployPipeline pipeline = new DeployPipeline(session);
        pipeline.cancel();

        DeployResult result = pipeline.execute(task(false), new RecordingListener());

        assertFalse(result.success());
        assertFalse(session.connected);
    }

    // ---------------------------------------------------------------- 历史备份清理

    @Nested
    @DisplayName("直接上传覆盖模式（不建暂存目录与备份）")
    class DirectUploadMode {

        private DeployTask directTask() {
            return task(false).setStagingEnabled(false);
        }

        @Test
        @DisplayName("目标目录已有历史文件时也能直接覆盖上传")
        void preExistingFilesInTargetDoNotBreakUpload() {
            FakeSshSession session = new FakeSshSession();
            // 目标目录里躺着上次部署留下的脚本与日志，它们不参与本次上传
            session.remoteDirectories.add("/srv/www/app");

            DeployResult result = new DeployPipeline(session).execute(directTask(), new RecordingListener());

            assertTrue(result.success(), result.message());
        }

        @Test
        @DisplayName("不创建暂存目录，也不产生任何备份")
        void noStagingNoBackup() {
            FakeSshSession session = new FakeSshSession();
            session.remoteDirectories.add("/srv/www/app");

            DeployResult result = new DeployPipeline(session).execute(directTask(), new RecordingListener());

            assertTrue(result.success(), result.message());
            assertTrue(session.renameCalls.isEmpty(),
                    "直接上传模式不应做目录改名: " + session.renameCalls);
            assertTrue(session.removed.stream().noneMatch(p -> p.contains("__staging_")),
                    "直接上传模式不应清理暂存目录: " + session.removed);
            assertTrue(session.removed.stream().noneMatch(p -> p.contains("__bak_")),
                    "直接上传模式不应删除备份: " + session.removed);
        }

        @Test
        @DisplayName("文件直接落在目标目录，可被覆盖")
        void writesDirectlyToTargetDirectory() {
            FakeSshSession session = new FakeSshSession();
            session.remoteDirectories.add("/srv/www/app");

            new DeployPipeline(session).execute(directTask(), new RecordingListener());

            assertTrue(session.writtenFiles.containsKey("/srv/www/app/app.jar"),
                    "应直接写入目标目录: " + session.writtenFiles.keySet());
        }
    }

    @Nested
    @DisplayName("历史备份清理")
    class BackupPruning {

        @Test
        @DisplayName("超出保留份数的历史备份被删除，最新的留下")
        void prunesBackupsBeyondLimit() {
            FakeSshSession session = withExistingBackups(5);
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(2);

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertTrue(result.success(), result.message());
            // 预置 5 份 + 本次新生成的 1 份 = 6 份，保留 2 份应删掉 4 份
            assertEquals(4, session.removed.size(), "应删除 4 个历史备份: " + session.removed);
            for (String path : session.removed) {
                assertTrue(path.contains(".__bak_"), "只能删备份目录: " + path);
            }
        }

        @Test
        @DisplayName("本次生成的备份永远不会被删")
        void neverRemovesTheBackupItJustCreated() {
            FakeSshSession session = withExistingBackups(3);
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(1);

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertTrue(result.success(), result.message());
            String created = result.backupDirectory();
            assertNotNull(created, "应报告本次备份目录");
            assertFalse(session.removed.contains(created),
                    "刚生成的备份是唯一的回滚点，绝不能被清理: " + session.removed);
        }

        @Test
        @DisplayName("保留份数为 0 时一个备份都不删")
        void keepZeroDisablesPruning() {
            FakeSshSession session = withExistingBackups(5);
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(0);

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertTrue(result.success(), result.message());
            assertTrue(session.removed.isEmpty(), "保留数为 0 表示不清理: " + session.removed);
        }

        @Test
        @DisplayName("切换失败时不清理任何备份")
        void doesNotPruneWhenActivateFails() {
            FakeSshSession session = withExistingBackups(5);
            session.failRenameWhenTargetIsFinalDirectory = true;
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(1);

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertFalse(result.success());
            assertEquals(DeployStage.ACTIVATE, result.stage());
            // 此刻所有备份都是用户的退路，删任何一个都可能让回滚无源
            assertTrue(session.removed.stream().noneMatch(p -> p.contains(".__bak_")),
                    "切换失败时不得删除备份: " + session.removed);
        }

        @Test
        @DisplayName("部署后命令失败时本次备份仍然保留")
        void keepsFreshBackupWhenPostCommandFails() {
            FakeSshSession session = withExistingBackups(5);
            session.execResults.add(new RemoteCommandResult(1, "", "boom"));
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(1);
            task.setAfterDeployCommand("./restart.sh");

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertFalse(result.success());
            assertEquals(DeployStage.POST_COMMAND, result.stage());
            // 清理按份数裁剪历史备份，本次新生成的那份是唯一的回退目标，必须还在
            assertFalse(session.removed.contains(result.backupDirectory()),
                    "重启失败时用户要靠本次备份回滚，不能删: " + session.removed);
            assertNotNull(result.backupDirectory());
        }

        @Test
        @DisplayName("首次部署不产生备份，但仍按份数裁剪历史备份")
        void firstDeployWithNoTargetStillPrunesToLimit() {
            FakeSshSession session = new FakeSshSession();
            session.remoteDirectories.addAll(List.of(
                    "/srv/www/app.__bak_20260101010101", "/srv/www/app.__bak_20260202020202"));
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(1);

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertTrue(result.success(), result.message());
            assertNull(result.backupDirectory(), "线上目录本来就不存在，不应有备份");
            // 保留数是「总共保留几份」，因此没有新备份时裁剪到 keep 份依然正确
            assertEquals(List.of("/srv/www/app.__bak_20260101010101"), session.removed,
                    "应裁剪到只剩最新的 1 份: " + session.removed);
        }

        @Test
        @DisplayName("列举父目录失败不影响部署结果")
        void listingFailureDoesNotBreakDeploy() {
            FakeSshSession session = withExistingBackups(3);
            session.failListDirectory = true;
            DeployTask task = task(false);
            task.getHost().setBackupKeepCount(1);

            DeployResult result = new DeployPipeline(session).execute(task, new RecordingListener());

            assertTrue(result.success(), "清理旧备份失败不应把已生效的部署判成失败: " + result.message());
        }

        /**
         * 预置线上目录与 N 份历史备份，模拟反复部署后堆积的现场。
         */
        private FakeSshSession withExistingBackups(int count) {
            FakeSshSession session = new FakeSshSession();
            session.remoteDirectories.add("/srv/www/app");
            for (int i = 1; i <= count; i++) {
                session.remoteDirectories.add(String.format("/srv/www/app.__bak_2026010%d000000", i));
            }
            return session;
        }
    }

    // ---------------------------------------------------------------- 多台部署

    @Nested
    @DisplayName("多台部署")
    class MultiHostDeploy {

        @Test
        @DisplayName("两台都成功：构建只做一次，逐台上传并按序执行各自命令")
        void bothHostsSucceed_WhenTwoHostsSelected() {
            FakeSshSession session = new FakeSshSession();
            RecordingListener listener = new RecordingListener();
            ServerProfile hostA = server("服务器A", "/srv/www");
            ServerProfile hostB = server("服务器B", "/srv/backup");

            DeployResult result = new DeployPipeline(session).executeAll(
                    List.of(taskOn(false, hostA).setPreUploadCommand("./prepA.sh")
                            .setAfterDeployCommand("./restartA.sh"),
                            taskOn(false, hostB).setPreUploadCommand("./prepB.sh")
                                    .setAfterDeployCommand("./restartB.sh")),
                    listener);

            assertTrue(result.success(), result.message());
            assertEquals("发布完成（2 台）", result.message());
            // 构建只有一个阶段事件（产物只构建一次），上传阶段出现两次
            assertEquals(1, countStages(listener, DeployStage.BUILD),
                    "两台共享一次构建: " + listener.stages.toString());
            assertEquals(2, countStages(listener, DeployStage.UPLOAD), listener.stages.toString());
            assertEquals(2, countStages(listener, DeployStage.ACTIVATE), listener.stages.toString());
            // 两台各自执行自己的命令，前一台完全结束才轮到后一台
            assertEquals(List.of("./prepA.sh", "./restartA.sh", "./prepB.sh", "./restartB.sh"),
                    session.execCalls, "命令应按服务器顺序依次执行");
            assertEquals(2, session.connectCount, "应连接两台服务器");
            assertEquals(2, session.openSftpCount, "每台一个 SFTP 通道");
            // 上传量累加：两个 11 字节的 app.jar
            assertEquals(2, result.uploadedFileCount());
            assertEquals(11L * 2, result.uploadedBytes());
        }

        @Test
        @DisplayName("某一台上传失败：立即中止，不再连接后续主机")
        void failsFast_WhenOneHostFails() {
            FakeSshSession session = new FakeSshSession();
            session.remoteDirectories.add("/srv/www/app");
            session.failUploadOnSftpOpen = 2; // 第二台开始时上传失败
            RecordingListener listener = new RecordingListener();
            ServerProfile hostA = server("服务器A", "/srv/www");
            ServerProfile hostB = server("服务器B", "/srv/backup");
            ServerProfile hostC = server("服务器C", "/srv/other");

            DeployResult result = new DeployPipeline(session).executeAll(
                    List.of(taskOn(false, hostA), taskOn(false, hostB), taskOn(false, hostC)),
                    listener);

            assertFalse(result.success());
            assertEquals(DeployStage.UPLOAD, result.stage());
            assertEquals(2, session.connectCount,
                    "第二台失败后第三台不应再被连接: connects=" + session.connectCount);
            assertTrue(result.message().contains("服务器B"), result.message());
            // 第一台已成功切换，第二台只留下清理动作
            assertEquals(1, countStages(listener, DeployStage.ACTIVATE), listener.stages.toString());
            assertTrue(session.removed.stream().anyMatch(p -> p.contains("__staging_")),
                    "失败台的暂存目录应被清理: " + session.removed);
        }

        @Test
        @DisplayName("一台失败后 message 标注失败服务器，单台部署则不标注")
        void failureMessageCarriesHostName_OnlyForMultiHost() {
            FakeSshSession session = new FakeSshSession();
            session.execResults.add(new RemoteCommandResult(1, "", "boom"));
            ServerProfile hostA = server("服务器A", "/srv/www");

            DeployResult multi = new DeployPipeline(session).executeAll(
                    List.of(taskOn(false, hostA).setPreUploadCommand("./prep.sh"),
                            taskOn(false, server("服务器B", "/srv/backup"))
                                    .setPreUploadCommand("./prep.sh")),
                    new RecordingListener());

            assertFalse(multi.success());
            assertEquals(DeployStage.PRE_COMMAND, multi.stage());
            assertTrue(multi.message().startsWith("服务器 服务器A 失败: "), multi.message());

            // 对照组：单台部署保持原始信息，不带服务器名前缀
            FakeSshSession singleSession = new FakeSshSession();
            singleSession.execResults.add(new RemoteCommandResult(1, "", "boom"));
            DeployResult single = new DeployPipeline(singleSession)
                    .execute(taskOn(false, server("服务器A", "/srv/www"))
                            .setPreUploadCommand("./prep.sh"), new RecordingListener());
            assertFalse(single.success());
            assertFalse(single.message().startsWith("服务器 "), single.message());
        }

        @Test
        @DisplayName("仅打包多台：构建一次即返回，不连接任何服务器")
        void packageOnlyForMultiHost_BuildsOnceWithoutConnecting() throws IOException {
            Files.writeString(projectRoot.resolve("build.sh"), "#!/bin/sh\n");
            FakeSshSession session = new FakeSshSession();
            RecordingListener listener = new RecordingListener();

            DeployResult result = new DeployPipeline(session).executeAll(
                    List.of(taskOn(true, server("服务器A", "/srv/www")).setBuildMode(BuildMode.UPLOAD_FILE),
                            taskOn(true, server("服务器B", "/srv/backup")).setBuildMode(BuildMode.UPLOAD_FILE)),
                    listener);

            assertTrue(result.success(), result.message());
            assertEquals(DeployStage.BUILD, result.stage());
            assertEquals(0, session.connectCount, "仅打包不连接任何服务器");
            assertEquals(0, session.openSftpCount);
            assertEquals(0, countStages(listener, DeployStage.UPLOAD));
        }

        @Test
        @DisplayName("两台均未配置部署后命令时成功消息带台数")
        void multiHostSuccessMessageCarriesSlashCount() {
            FakeSshSession session = new FakeSshSession();
            DeployPipeline pipeline = new DeployPipeline(session);

            DeployResult two = pipeline.executeAll(
                    List.of(taskOn(false, server("A", "/srv/www")), taskOn(false, server("B", "/srv/backup"))),
                    new RecordingListener());
            assertTrue(two.success());
            assertEquals("发布完成（2 台）", two.message());

            DeployResult single = pipeline.execute(taskOn(false, server("A", "/srv/www")),
                    new RecordingListener());
            assertTrue(single.success());
            assertEquals("发布完成", single.message());
        }
    }

    private static int countStages(RecordingListener listener, DeployStage stage) {
        return (int) listener.stages.stream().filter(s -> s == stage).count();
    }

    /**
     * 构造一个远端基目录可定制的服务器，便于多台场景区分目标路径。
     */
    private static ServerProfile server(String name, String remoteBaseDir) {
        ServerProfile host = new ServerProfile(name, "10.0.0.1", 22, "app");
        host.setId("host-" + name);
        host.setRemoteBaseDir(remoteBaseDir);
        return host;
    }

    private DeployTask taskOn(boolean packageOnly, ServerProfile host) {
        return new DeployTask(projectRoot.toFile(), host)
                .setBuildMode(BuildMode.UPLOAD_FILE)
                .setLocalPath("app.jar")
                .setTargetDirectory("app")
                .setHost(host)
                .setStagingEnabled(true)
                .setPackageTimeoutMs(60_000)
                .setPackageOnly(packageOnly);
    }

    // ---------------------------------------------------------------- 辅助

    private DeployTask task(boolean packageOnly) {
        ServerProfile host = new ServerProfile("测试服务器", "10.0.0.1", 22, "app");
        host.setId("test-host");
        host.setRemoteBaseDir("/srv/www");
        return new DeployTask(projectRoot.toFile(), host)
                .setBuildMode(BuildMode.UPLOAD_FILE)
                .setLocalPath("app.jar")
                .setTargetDirectory("app")
                .setHost(host)
                .setStagingEnabled(true)
                .setPackageTimeoutMs(60_000)
                .setPackageOnly(packageOnly);
    }

    /**
     * 记录事件顺序的监听器。
     */
    private static final class RecordingListener implements DeployListener {

        private final List<DeployStage> stages = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private int cancelledCount;
        private int failedCount;

        @Override
        public void onStageStart(@NotNull DeployStage stage) {
            stages.add(stage);
        }

        @Override
        public void onEvent(@NotNull DeployEvent event) {
            messages.add(event.level() + " " + event.text());
        }

        @Override
        public void onCancelled() {
            cancelledCount++;
        }

        @Override
        public void onFailure(@NotNull DeployException exception) {
            failedCount++;
        }
    }

    /**
     * 内存版 SSH/SFTP。所有远端路径都按字符串处理，不做真实 IO。
     */
    private static final class FakeSshSession implements SshSession {

        private final List<String> remoteDirectories = new ArrayList<>(List.of("/srv/www"));
        private final List<String> renameCalls = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final List<String> execCalls = new ArrayList<>();
        private final List<RemoteCommandResult> execResults = new ArrayList<>();

        private boolean connected;
        private int openSftpCount;
        /** 记录 connect 调用次数，多台部署时用于断言「失败后不再连接后续主机」 */
        private int connectCount;
        /** 第几次 openSftp 时让后续上传失败；0 表示永不失败 */
        private int failUploadOnSftpOpen;
        private String lastStagingDirectory = "";
        private boolean failUpload;
        private boolean failRenameWhenTargetIsFinalDirectory;

        /** 让 listDirectory 抛异常，模拟远端无权限列举父目录 */
        private boolean failListDirectory;
        private long remoteFileCountOverride = -1;
        private long remoteSizeOverride = -1;
        private final java.util.Map<String, Long> writtenFiles = new java.util.LinkedHashMap<>();
        /** 第几次 put 时触发取消；0 表示不取消 */
        private int cancelOnPutCount;
        /** 是否在远端命令执行时触发取消 */
        private boolean cancelOnExec;

        @Override
        public @NotNull String connect(@NotNull ServerProfile profile, @NotNull String password) {
            connected = true;
            connectCount++;
            return profile.getHosts().isEmpty() ? "" : profile.getHosts().get(0);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public @NotNull SftpChannel openSftp() {
            openSftpCount++;
            if (failUploadOnSftpOpen > 0 && openSftpCount >= failUploadOnSftpOpen) {
                failUpload = true;
            }
            return new FakeSftpChannel();
        }

        @Override
        public @NotNull RemoteCommandResult exec(@NotNull String command,
                                                 String workingDir,
                                                 int timeoutMs,
                                                 OutputListener outputListener) {
            return exec(command, workingDir, timeoutMs, outputListener, null);
        }

        @Override
        public @NotNull RemoteCommandResult exec(@NotNull String command,
                                                 String workingDir,
                                                 int timeoutMs,
                                                 OutputListener outputListener,
                                                 com.hql.deployer.core.DeployCancellation cancellation) {
            execCalls.add(command);
            if (cancelOnExec) {
                // 模拟"命令已发出、用户此时点了停止"
                cancellation.cancel();
                cancellation.throwIfCancelled(DeployStage.POST_COMMAND);
            }
            return execResults.isEmpty()
                    ? new RemoteCommandResult(0, "", "")
                    : execResults.remove(0);
        }

        @Override
        public void close() {
            connected = false;
        }

        String lastStagingDirectory() {
            return lastStagingDirectory;
        }

        private final class FakeSftpChannel implements SftpChannel {

            private int putCount;

            @Override
            public void mkdirs(@NotNull String remotePath) {
                remoteDirectories.add(remotePath);
            }

            @Override
            public void put(@NotNull File localFile, @NotNull String remotePath, String mode) {
                putCount++;
                if (failUpload) {
                    throw new com.hql.deployer.ssh.SshException("模拟上传失败");
                }
                if (cancelOnPutCount == putCount) {
                    // 模拟"传到一半用户点了停止"：抛取消异常，与真实实现的中断路径一致
                    throw new com.hql.deployer.core.DeployCancelledException(DeployStage.UPLOAD);
                }
                writtenFiles.put(remotePath, localFile.length());
            }

            @Override
            public boolean removeAll(@NotNull String remotePath) {
                removed.add(remotePath);
                remoteDirectories.remove(remotePath);
                return true;
            }

            @Override
            public boolean exists(@NotNull String remotePath) {
                return remoteDirectories.contains(remotePath);
            }

            @Override
            public void rename(@NotNull String sourcePath, @NotNull String targetPath) {
                if (targetPath.contains(".__staging_")) {
                    lastStagingDirectory = targetPath;
                }
                // 只让"暂存目录 → 线上目录"这一步失败，还原动作必须能成功
                if (failRenameWhenTargetIsFinalDirectory
                        && sourcePath.contains(".__staging_")
                        && targetPath.endsWith("/app")) {
                    throw new com.hql.deployer.ssh.SshException("模拟切换失败");
                }
                renameCalls.add(sourcePath + " -> " + targetPath);
                remoteDirectories.remove(sourcePath);
                remoteDirectories.add(targetPath);
            }

            @Override
            public Stats statDirectory(@NotNull String remotePath) {
                if (remoteFileCountOverride >= 0) {
                    return new Stats(remoteFileCountOverride, remoteSizeOverride);
                }
                return new Stats(1, 11L);
            }

            @Override
            @NotNull
            public List<String> listDirectory(@NotNull String remotePath) {
                if (failListDirectory) {
                    throw new com.hql.deployer.ssh.SshException("模拟列举目录失败: " + remotePath);
                }
                String prefix = remotePath.endsWith("/") ? remotePath : remotePath + "/";
                return remoteDirectories.stream()
                        .filter(path -> path.startsWith(prefix))
                        // 只返回直接子项：剩下的部分不能再含 '/'
                        .filter(path -> !path.substring(prefix.length()).contains("/"))
                        .map(path -> path.substring(prefix.length()))
                        .toList();
            }

            @Override
            public void cd(@NotNull String remotePath) {
                mkdirs(remotePath);
            }

            @Override
            public void close() {
            }
        }
    }

    @Nested
    @DisplayName("取消语义")
    class Cancellation {

        @Test
        @DisplayName("isCancelled 反映 cancel 调用")
        void cancelFlagIsVisible() {
            DeployPipeline pipeline = new DeployPipeline(new FakeSshSession());

            assertFalse(pipeline.isCancelled());
            pipeline.cancel();
            assertTrue(pipeline.isCancelled());
        }

        @Test
        @DisplayName("预先取消：走 onCancelled 而非 onFailure，且不建立连接")
        void preCancelledUsesCancelledCallback() {
            FakeSshSession session = new FakeSshSession();
            RecordingListener listener = new RecordingListener();
            DeployPipeline pipeline = new DeployPipeline(session);
            pipeline.cancel();

            DeployResult result = pipeline.execute(task(false), listener);

            assertTrue(result.cancelled(), "应标记为取消而非失败: " + result.message());
            assertFalse(result.success());
            assertEquals(1, listener.cancelledCount, "应回调 onCancelled");
            assertEquals(0, listener.failedCount, "取消不应回调 onFailure，否则会弹红色报错");
            assertFalse(session.connected, "取消后不应连接服务器");
        }

        @Test
        @DisplayName("上传途中取消：清理暂存目录，绝不发生目录切换")
        void cancelDuringUploadCleansStaging() {
            FakeSshSession session = new FakeSshSession();
            // 线上目录已存在，验证"取消不会把线上换成新版本"
            session.remoteDirectories.add("/srv/www/app");
            session.cancelOnPutCount = 1;
            RecordingListener listener = new RecordingListener();

            DeployResult result = new DeployPipeline(session).execute(task(false), listener);

            assertTrue(result.cancelled(), result.message());
            assertEquals(1, listener.cancelledCount);
            assertEquals(0, listener.failedCount);
            assertTrue(session.renameCalls.isEmpty(),
                    "取消发生在激活之前，不应有任何 rename: " + session.renameCalls);
            assertTrue(session.removed.stream().anyMatch(path -> path.contains(".__staging_")),
                    "未激活的暂存目录必须清理: " + session.removed);
        }

        @Test
        @DisplayName("上传前命令执行期间取消：未生效内容被清理，线上服务未受影响")
        void cancelDuringPreCommandReportsNoActivation() {
            FakeSshSession session = new FakeSshSession();
            session.remoteDirectories.add("/srv/www/app");
            session.cancelOnExec = true;
            RecordingListener listener = new RecordingListener();

            DeployResult result = new DeployPipeline(session)
                    .execute(task(false).setPreUploadCommand("./prep.sh"), listener);

            assertTrue(result.cancelled(), result.message());
            assertEquals(1, listener.cancelledCount);
            assertEquals(0, listener.failedCount);
            // 取消发生在上传之前，暂存目录尚未创建也不应被清理，更不能有切换
            assertTrue(session.renameCalls.isEmpty(),
                    "上传前命令取消后不应有任何 rename: " + session.renameCalls);
            assertTrue(session.writtenFiles.isEmpty());
            assertTrue(listener.messages.stream().anyMatch(text -> text.contains("尚未开始")),
                    "上传前命令取消属于未生效场景，应提示尚未开始上传: " + listener.messages);
        }

        @Test
        @DisplayName("激活之后取消：如实报告已生效，不谎称线上未受影响")
        void cancelAfterActivateReportsActivation() {
            FakeSshSession session = new FakeSshSession();
            // 在远端命令等待时点停止：此时发布已完成，取消不能回滚
            session.cancelOnExec = true;
            RecordingListener listener = new RecordingListener();

            DeployResult result = new DeployPipeline(session)
                    .execute(task(false).setAfterDeployCommand("./restart.sh"), listener);

            assertTrue(result.cancelled(), result.message());
            assertTrue(session.renameCalls.stream().anyMatch(call -> call.endsWith(" -> /srv/www/app")),
                    "激活已完成，不应回滚: " + session.renameCalls);
            assertTrue(listener.messages.stream().anyMatch(text -> text.contains("已生效")),
                    "必须提示发布内容已生效，否则用户会误以为线上还是旧版本: " + listener.messages);
        }

        @Test
        @DisplayName("取消后不发送失败通知的路径由结果标志区分")
        void cancelledResultIsDistinguishable() {
            FakeSshSession session = new FakeSshSession();
            DeployPipeline pipeline = new DeployPipeline(session);
            pipeline.cancel();

            DeployResult result = pipeline.execute(task(false), new RecordingListener());

            // 通知层只按 cancelled 决定是否弹窗，因此这个标志必须可靠置位
            assertTrue(result.cancelled());
            assertFalse(result.success());
        }
    }

    @Nested
    @DisplayName("formatSize")
    class FormatSize {

        @Test
        @DisplayName("按量级选择单位")
        void picksUnit() {
            assertEquals("0 B", DeployPipeline.formatSize(0));
            assertEquals("1023 B", DeployPipeline.formatSize(1023));
            assertEquals("1.0 KB", DeployPipeline.formatSize(1024));
            assertEquals("1.0 MB", DeployPipeline.formatSize(1024L * 1024));
            assertEquals("1.00 GB", DeployPipeline.formatSize(1024L * 1024 * 1024));
        }
    }
}
