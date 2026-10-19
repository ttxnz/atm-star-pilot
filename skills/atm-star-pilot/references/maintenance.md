# 接入、热加载与分享

项目根含 mcp_server.py、build.gradle、src/，分享包另有 release/、SHARE_MANIFEST.json 和 skills/atm-star-pilot/。实例根直接含 mods/。不把 skill 目录当游戏项目，不复用作者路径。

只读预检：`python <skill>/scripts/inspect_setup.py --project <project> --instance <instance>`。只查文件、JAR 和非敏感配置摘要，不读会话令牌，不连接游戏，不写配置。

## 接收者安装

自行准备 ATM10 v8.2 / Minecraft 1.21.1 / NeoForge 21.1.251、匹配 JEI、Python 3.10+ 和整合包要求的 Java 21。在自己选定且已退出的实例执行：

```powershell
python tools/share/install_client.py --instance "<instance>"
```

核心安装到 mods/atm-star-pilot-0.1.0.jar；热模块到 config/atmpilot/runtime/automation.jar，不要把热模块放 mods/。已有同名组件不同时需明确 --replace-existing；已有规则和流程保留。安装器不替使用者关闭、启动或重启游戏。

进入世界后本机生成 config/atmpilot/session.json，供本机 stdio MCP 读取：

```toml
[mcp_servers.minecraft]
command = "python"
args = ["<project>/mcp_server.py", "--session", "<instance>/config/atmpilot/session.json"]
startup_timeout_sec = 15
tool_timeout_sec = 60
```

不打印或发送 session 内容。接收者 buildAreas 默认空；飞行、旅行、死亡恢复和共享取料按当前用户授权配置，不能套用原世界权限。

## 热更新

- JSON：改实例 workflows/*.json，预览后下次提交使用新快照，不会中途改当前任务。
- 热模块：改 src/automation/java，构建 automationJar。每个任务冻结旧 JAR，后续加载新版本。
- 核心：通常自然退出后换 JAR；开发热替换仅限 JVM 支持的类结构。

构建需完整 JDK 21、JAVA_HOME 及兼容 JEI 编译依赖。分享包可用 tools/share/prepare_build.py --jei <jei.jar> 准备；其他模组 JAR 不随包分发。

```powershell
./gradlew.bat assemble --max-workers=1
python tools/deploy_runtime.py --no-build --session "<instance>/config/atmpilot/session.json"
```

deploy_runtime 会实际更新实例，仅在任务包含部署时运行。只打包时构建即可，不顺带部署或重启。

核心 hotpatch 前确认没有运行 job、光标为空，传自己的 --jdk 与 --session。新增 lambda 可能新增 synthetic 方法，导致表面上的方法体修改也被 JVM 拒绝；保持已加载类结构一致。拒绝不等于生效，核对结果与当前进程。

stage_core.py --no-build 是等待游戏自然退出的暂存工具，不用于首次安装。核心 API 与热模块须配套升级并核对 SHA256，不能宣称未加载的新源码已在当前 JVM 运行。

## 分享

同步 skill 的项目副本 skills/atm-star-pilot 后构建两个 JAR：

```powershell
./gradlew.bat assemble --max-workers=1
python tools/package_share.py --date YYYY-MM-DD
```

打包器生成源码、两个预编译 JAR、MCP、常驻队列、电脑程序、安装模板和 skill 的分享包，另生成单独 skill ZIP。可用 --output / --skill-output 选路径，--expected-core-sha / --expected-runtime-sha 固定快照。

核对 SHARE_MANIFEST.json、ZIP 的 SHA256 和逐文件校验；检查新增 Python 依赖没有漏装。安装器在临时实例模拟验证，不用运行中的实例试装。

排除 session、令牌、登录资料、作者坐标／权限、个人进度、旧队列、运行证据、日志、截图、存档、缓存和其他模组 JAR。个人断点保留在原项目，分享包使用独立默认守则。

只打包不做游戏内验收。交付主分享包和 skill 包，说明调用名与安装位置，不把开发快照宣传为完整自动通关方案。
