# Deploy to Host

一个 IntelliJ IDEA 平台插件，用于把 Maven 构建产物或前端静态资源一键部署到远程主机（SSH/SFTP）。支持多模块 Maven 项目、多台服务器批量发布、暂存目录原子切换与历史备份，任一步骤失败立即中止，保证线上服务不受影响。

> 插件 ID：`com.hql.maven-quick-deploy` · 作者：hql

## 功能特性

- **一键打包 / 一键发布**：在 ToolWindow 中选中 Maven 模块与部署配置即可完成构建并发布；也可在 Maven 工具窗口右键「打包选中模块」。
- **三种构建模式**：
  - `Maven 构建`：执行 `mvn` 打包后上传模块产物（如 `target` 目录）
  - `上传文件/目录`：不执行构建，直接上传本地已有的文件或目录（静态资源场景）
  - `自定义命令`：执行任意自定义构建命令（npm / gradle / sh 脚本等）
- **多服务器支持**：一个部署配置可绑定多台服务器，构建只做一次，随后逐台完成「连接 → 上传前命令 → 上传 → 生效 → 部署后命令」。
- **暂存目录 + 原子切换**：所有文件先经 SFTP 上传到远端暂存目录，生效阶段用单条 `mv` 原子切换到线上目录，避免上传中断破坏线上文件。
- **历史备份**：每次切换前把在线目录改名备份，并可按保留份数自动清理过期备份（默认保留 3 份）。
- **发布前后命令**：上传前与部署后均可执行远端命令，如执行重启脚本。
- **快失败流水线**：六阶段严格串行（预检 → 构建 → 上传前命令 → 上传 → 生效 → 部署后命令），任一阶段失败立即中止并给出定位诊断。
- **排除规则与权限**：上传支持自定义排除规则与远端权限（chmod）。
- **密码安全托管**：服务器密码交由 IDE 的 PasswordSafe 加密存储，明文不落盘。
- **构建 JDK 自适应**：自动用 IDEA 项目 SDK 覆盖子进程 `JAVA_HOME`，避免本机 `JAVA_HOME` 与 `pom.xml` 要求版本不一致导致编译失败。
- **支持取消**：取消动作在各阶段执行循环中被观察（构建进程被杀、下一文件前停手、远端命令通道断开），并如实提示线上受影响范围。

## 快速上手

1. 打开任意 Maven（或静态资源）项目，IntelliJ IDEA 2026.2+。
2. 在 **设置 → Tools → Deploy to Host** 中维护服务器列表（主机、端口、用户名、密码、远端根目录、超时、备份保留份数）。
3. 通过 **Run/Debug Configurations → + → 部署到服务器** 新建部署配置，选择构建模式、模块、待上传路径与目标目录。
4. 在右侧 **Deploy to Host** 工具窗口选择模块与部署配置，点击 **一键部署**（或 **仅打包**）；也可直接在 Maven 工具窗口右键模块「打包选中模块」。

## 部署流水线

以下任一阶段失败，流水线立即中止；不同阶段失败对线上服务的影响已做最小化保证：

| 阶段 | 说明 | 失败时线上影响 |
| --- | --- | --- |
| 预检 | 校验服务器配置、目标目录、构建命令存在性 | 线上无任何改动 |
| 构建 | 按模式执行 Maven / 自定义命令；直接上传模式跳过 | 线上无任何改动 |
| 上传前命令 | 在远端执行可选命令 | 尚未上传任何字节，线上无改动 |
| 上传 | SFTP 上传本地目录/文件到远端暂存目录 | 未生效前中断则清理暂存目录，线上目录原样 |
| 生效 | 原目录改名备份，暂存目录 `mv` 原子切换到线上 | 切换失败自动还原备份目录 |
| 部署后命令 | 执行可选的重启等命令 | 新文件已就位但进程未重启（旧进程继续服务），并提示回滚路径 |

启用暂存目录时，最坏情况不会让服务中断：上传失败时线上一个字节都没动；部署后命令失败时新文件已就位但旧进程仍持有旧文件句柄继续服务。

## 环境要求

| 项 | 要求 |
| --- | --- |
| IDE | IntelliJ IDEA 2026.2 及以上（`pluginSinceBuild = 262`，未设 `untilBuild`） |
| 编译 JDK | JDK 25（`build.gradle.kts` toolchain 指定） |
| Gradle 运行 JVM | JDK 17+（`gradle.properties` 中 `org.gradle.java.home` 指定） |
| 依赖库 | `com.github.mwiede:jsch:0.2.24`（SSH/SFTP）、IntelliJ Platform Gradle Plugin `2.19.0` |

> 本机默认 `JAVA_HOME` 若是 JDK 8，可直接使用仓库提供的 `gw.bat`（脚本内置 JDK 17 的 `JAVA_HOME`，编译仍按 toolchain 使用 JDK 25）。

## 构建与测试

```bash
# 打包插件（产物在 build/distributions 下）
.\gw.bat buildPlugin

# 插件验证（IDE 兼容性检查）
.\gw.bat verifyPlugin

# 启动沙箱 IDE 调试
.\gw.bat runIde

# 运行单元测试（JUnit 4 + JUnit 5）
.\gw.bat test
```

Windows 下直接执行 `gradlew.bat` 亦可，但需保证环境 `JAVA_HOME` 为 JDK 17+。

## 目录结构

```
src/main/java/com/hql/deployer/
├── action/        Menu action（Maven 工具窗口「打包选中模块」）
├── config/        服务器配置模型（ServerProfile）、服务、PasswordSafe 密码托管
├── core/          部署领域模型（阶段、事件、结果、取消、异常）
├── maven/         本地构建执行（LocalBuildRunner）、模块扫描、构建 JDK 解析
├── pipeline/      发布流水线（DeployPipeline）与任务输入（DeployTask）
├── runconfig/     Run/Debug Configuration（类型、工厂、编辑器、ProgramRunner、日志控制台）
├── ssh/           SSH/SFTP 传输层（JSch 会话、SFTP 上传器、异常与结果）
├── ui/            ToolWindow 一键部署面板、设置页、服务器配置对话框
└── util/          路径处理、排除规则匹配、通知、国际化 Bundle

src/main/resources/
├── META-INF/plugin.xml              插件声明（含 Maven 插件可选依赖扩展）
└── messages/DeployBundle.properties 界面文案
```

## 测试

`src/test/java/com/hql/deployer/` 下覆盖了路径处理、排除规则、发布流水线、本地构建执行与密码凭据构造等核心逻辑的单元测试。

## 兼容性说明

- 插件仅依赖 `com.intellij.modules.platform`；Maven 工具窗口右键入口声明为 `org.jetbrains.idea.maven` 的可选依赖，未安装 Maven 插件时该入口自动隐藏，其余功能不受影响。
- 构建源码编码固定为 `UTF-8`，避免 Windows 默认 GBK 环境下中文注释乱码或编译失败。