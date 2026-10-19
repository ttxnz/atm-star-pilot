# ATM Star Pilot 分享版

这是开发中的 **Minecraft 客户端执行 Mod + Python MCP**，面向 **ATM10 v8.2 / Minecraft 1.21.1 / NeoForge 21.1.251**。外部智能体读取现场、规划并提交动作，Mod 在客户端游戏线程执行正常生存操作。

**它还不能一键完成 ATM10，也没有完成所有模组适配或做出 ATM 之星。** 本包提供目前的源码和预编译组件，适合继续开发或接入支持 MCP 的智能体。服务端配方、机器版本、键位、权限和世界布局不同，已有流程可能需要调整。

**2026-10-16 开发快照：** 包含逐 tick 飞行控制、本地反击与阻挡处理、可追加的常驻任务队列、原生地图路径点／TP、现成下界门旅行、AE 材料回存、CC:Tweaked 掉落物分流，以及配套 `atm-star-pilot` skill。核心与热模块一起构建；JAR 名仍使用开发版本 0.1.0，以本包日期和 SHA256 区分快照。

## 文件

- `release/atm-star-pilot-0.1.0.jar`：安装到实例 `mods/` 的客户端核心。
- `release/atm-star-pilot-0.1.0-automation.jar`：安装到实例 `config/atmpilot/runtime/automation.jar` 的可热加载执行模块；**不要放入 mods/**。
- `src/`、Gradle wrapper、构建配置：Java 21 源码和构建入口，包括单元测试源码。
- `mcp_server.py`、`workflow_store.py`、`work_queue.py`：Python 3.10+，仅使用标准库的 stdio MCP 服务和本地常驻队列。
- `tools/run_queue.py`：按真实 job 和前后条件自动衔接已准备的生产阶段。
- `computer-programs/loot-router.lua`：自有独立有线网络中的可堆叠材料／装备回收分流程序。
- `skills/atm-star-pilot/`：配套技能、操作参考和只读接入检查脚本；另提供单独的 skill ZIP。
- `workflows/`：参数化 JSON 工作流示例。
- `server-rules.example.json`、`AGENTS.md`、`RULES_GUIDE.zh-CN.md`：默认关闭施工和共享仓储操作的独立配置与协作约定。
- `tools/`：运行模块部署、核心更新暂存、开发用热补丁和参考数据提取；`tools/share/` 提供分享版安装与构建准备工具。
- `SHARE_MANIFEST.json`：包内文件的 SHA256、预编译组件来源摘要和分享时的规范化记录。

没有包含 ATM10 整合包、其他模组 JAR、游戏存档、个人坐标/运行快照、服务器连接地址、会话文件或令牌。旧的个人进度文档和个人授权没有带入。Gradle wrapper 附带其 Apache 许可；项目自身使用 MIT 许可。

## 首次安装

接收者需要自行安装 ATM10 v8.2，保留 JEI。核心元数据要求 Minecraft `1.21.1`、NeoForge `>=21.1.251,<21.2`、JEI `>=19.57.0`。本 Mod 仅装客户端；不要为了它改动多人服务器的模组集。不同整合包版本尚未保证兼容。

1. 解压到独立项目目录。安装 Python 3.10+；游戏使用整合包要求的 Java 21。
2. **退出要安装的游戏实例**。将下面路径换成自己的实例根目录；该目录应直接含有 `mods/`：

   ```powershell
   python tools/share/install_client.py --instance "D:/Your-ATM10-Instance"
   ```

   工具只复制本包的两个组件；已有同名组件不同时会拒绝覆盖。明确需要更新时，在游戏退出后加 `--replace-existing`。已有 `server-rules.json` 和同名工作流保留原样；首次安装才写入默认安全模板。工具不会启动、关闭或重启游戏。

   还会补充缺失的 `computer-programs/*.lua`；已有电脑程序保留，不能把本包程序直接接到其他玩家库存网络。

   也可以手动复制核心到 `mods/`、运行模块到 `config/atmpilot/runtime/automation.jar`，将规则示例复制为 `config/atmpilot/server-rules.json`。

3. 按 `RULES_GUIDE.zh-CN.md` 调查自己的世界并配置施工区域。默认 `buildAreas=[]`，**没有任何施工授权**；共享仓储、已有飞行、旅行指令、自动死亡恢复和生存守卫均默认未授权。
4. 启动实例并进入自己的世界/服务器。**F7** 打开控制面板，**F8** 紧急停止。默认普通键鼠输入也会让出控制；本包不继承原使用者的全自动授权。
5. 核心会在**本机实例**生成 `config/atmpilot/session.json`。这是本机认证信息，只交给本机 MCP 程序读取，不要发给别人或粘贴到聊天。
6. 在支持 stdio MCP 的客户端添加 `mcp-config.example.toml` 中的条目，把两个路径换成自己的路径。例如：

   ```toml
   [mcp_servers.minecraft]
   command = "python"
   args = ["D:/ATM-Star-Pilot/mcp_server.py", "--session", "D:/Your-ATM10-Instance/config/atmpilot/session.json"]
   startup_timeout_sec = 15
   tool_timeout_sec = 60
   ```

   Python 不在 PATH 时，将 `command` 改为自己的 `python.exe` 绝对路径。必须使用当前实例生成的会话路径；示例路径只是占位符。

Mod 不会在启动时自动推产线。智能体应先调用 `minecraft_status`、`minecraft_inventory` 和实际配方/区域查询，再提交正常动作。`minecraft_live` 读取轻量实时角色和附近实体信息。

人工/F8 停止产生的暂停不能靠反复派发普通任务解除。确认现场、光标物品和下一步后，由当前使用者明确授权恢复，再以 `extension:recovery_resume` 作为恢复流程的首步。停止不会撤销已经完成的放置、投料或合成。

## 配套 skill

将 `skills/atm-star-pilot` 整个文件夹复制到接收者自己的技能目录，通常为 `~/.codex/skills/atm-star-pilot`；也可将单独 skill ZIP 解压到该技能目录。调用名为 **`$atm-star-pilot`**。

Skill 包含独立基地保护、效率路线、菜单与断点处理、批量队列、旅行／死亡恢复、热加载和分享打包说明。只读检查示例：

```powershell
python skills/atm-star-pilot/scripts/inspect_setup.py --project . --instance "D:/Your-ATM10-Instance"
```

检查脚本不读取 session 令牌、不连接或操作游戏；它不能代替进入游戏后的真实状态、库存与配方读取。

## 目前实现范围

- 实际角色、背包、当前容器、物品组件、已加载区块和附近实体读取；JEI 配方索引、FTB 任务读取、依赖规划与短缺估算。
- 正常移动、有限地面寻路、已解锁生存飞行的操控、容器搬运、原生合成、蓝图放置和批量动作队列。
- 自有区域的 AE2 存取/原生样板与订单，以及 Mekanism、Industrial Foregoing、Pipez、Botany Pots 等若干专用流程；具体适配见源码和 MCP 工具描述。
- 对已支持敌对生物的有界近战/局部反应，以及已支持 Shrink 物品的动物捕捉流程；这些能力仍受距离、视线、物品、能量和服务器判定限制。
- 可配置的 `/home`、`/back`、已确认传送台、自己的墓碑恢复和生存守卫；它们依赖当前使用者的明确授权与现场条件，模板不默认启用。
- 原生地图路径点、真实权限下的地图 TP 与已确认现成下界门的正常穿门流程；交通点不会扩大施工授权。
- 外部 JSON 工作流与运行模块热加载；失败停止、状态记录和中断后重规划。

## 连续生产与掉落物

准备实例 `config/atmpilot/work-queue.json` 后，从项目根运行：

```powershell
python tools/run_queue.py --session "D:/Your-ATM10-Instance/config/atmpilot/session.json"
```

只执行具体已准备阶段，不能自行思考攻略。以真实基线 afterJob 起步，检查阶段前后条件；执行时只能向同 generation 原子追加新 id。失败、其他 job 接管、光标非空或断点变化时停止后续派发，先检查现场，不重放部分投料。分享包不附带原世界的队列和检查点。

Lua 分流程序只用于已核对归属的独立自有 CC 有线库存网络。它要求三类库存前缀各自唯一：sophisticatedstorage:barrel_、apotheosis:salvaging_table_、minecraft:barrel_；有歧义时拒绝运行。正常导入／运行前确认拓扑，不自动修改世界。

其策略为装备／武器／工具等不可堆叠物和背包不进 AE，其余可堆叠材料包括带组件材料与宝石可以输出；装备按回收台原生接受规则处理，拒绝的保留。电脑输出计数不等于 AE 收货证明，仍须核对实际回流。

**能力限制：** 依赖展开只是估算，全星路线可能触及搜索上限；概率资源、魔法仪式、多方块、特殊 GUI、各种流体/化学品与未适配怪物不能视为已经支持。未打开容器和未加载区块不可凭空读取。队列被接受不代表完成；超时或中断后先查 `minecraft_job`、库存和现场，避免重复投料/订单。机器吞吐、能源、权限和任务进度由服务器及原模组实际决定。

## 热更新

**JSON 工作流：** 将文件放入实例 `config/atmpilot/workflows/`。`minecraft_workflows` 列举或预览，`minecraft_run_workflow` 每次读取最新文件。执行中的任务使用提交时的固定快照；编辑文件不会改写正在执行的动作。更改生产流程前先停止并读现场，再重规划。

**运行模块：** 编译后执行下面命令，替换文件采用原子操作，后续任务会加载新模块，正在运行的任务保留旧快照：

```powershell
./gradlew.bat automationJar --max-workers=1
python tools/deploy_runtime.py --no-build --session "D:/Your-ATM10-Instance/config/atmpilot/session.json"
```

分享包的预编译文件在 `release/`，开发工具默认使用新构建的 `build/libs/`。不编译、只复制本包预编译运行模块时，可以手动将 `release/atm-star-pilot-0.1.0-automation.jar` 复制到实例的 `runtime/automation.jar`；先停止并确认游戏物品状态。

**核心：** 常规核心升级需要在游戏退出后替换 `mods/` 中的 JAR，并下次正常启动生效。`tools/stage_core.py` 是 Windows 上等待当前游戏自然退出的一次性暂存工具，依赖当前有效会话及已安装旧核心，不用于首次安装。它只更新本 Mod。

开发用 `tools/hotpatch.py` 通过 JDK Attach 更新受限的核心类方法体，要求任务已停止、光标为空，并明确传 `--jdk` 与 `--session`。普通 JVM 不支持任意字段/方法结构变化，不能把它当作通用免重启。新运行模块依赖新增核心 API 时，应一起升级核心；不要跨版本混搭。

## 从源码构建

需要完整 JDK 21（含 `javac`）、网络以下载 Gradle/NeoForge 依赖。分享版删除了作者机器的 JDK 路径，请设置自己的 `JAVA_HOME`，并让 Gradle 能发现 JDK 21。

JEI 是编译期依赖，未随本包分发。用自己实例中相匹配的 JEI JAR 准备它：

```powershell
python tools/share/prepare_build.py --jei "D:/Your-ATM10-Instance/mods/jei-1.21.1-neoforge-YourVersion.jar"
./gradlew.bat assemble --max-workers=1
```

生成的核心和运行模块在 `build/libs/`。Java 测试源码和 Python 测试源码保留；接收者需要时可自行运行。分享打包没有启动游戏、重启实例或再次执行游戏验收。

`tools/import_pack.py` 仅解析本地整合包脚本文本、不执行 JavaScript。分享版通过 `ATM_PILOT_INSTANCE` 环境变量指定自己的实例目录；附带 ATM10 v8.2 配方/结构事实仅供参考，服务器同步的实际 JEI 配方优先。

## 分享版规范化

打包只处理临时副本，没有更改原项目运行中的源码、实例配置或会话。分享版中移除了本机默认路径，生成了无个人授权的规则、MCP 配置和协作守则；所有者匹配测试中的个人样本被换成固定虚构 UUID 及其对应整数数组。项目生产 Java 源码和两个预编译 JAR 保持原样。具体文件和校验值见清单。

本包的规则检查帮助避免误操作，但它仍是开发中的本机执行工具，接收者应只连接可信的本机智能体。请遵守服务器对客户端自动化的规定，并由自己的使用者决定授权范围。
