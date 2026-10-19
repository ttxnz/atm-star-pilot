# ATM Star Pilot · Minecraft MCP

让外部 AI 读取 Minecraft 现场、制定计划，并通过客户端 Mod 执行正常生存操作。提供批量工作流、可热加载的 Java 执行模块，以及开发中的原版生存通关模式。

**这是开发中的自动化工具，不是一键通关成品。尚未完成 ATM 之星，也没有验证原版模式从空背包到击杀末影龙的完整流程。**

## 两种模式

| 模式 | 用途 | 当前状态 |
| --- | --- | --- |
| ATM10 辅助自动化 | 外部智能体规划产线，通过 MCP 操作仓储、合成、机器和建造 | 部分流程在 ATM10 实例中运行过，不保证其他机器或整合包版本直接兼容 |
| 原版生存模式 | 从零采集、制作装备、进入下界、寻找要塞和挑战末影龙 | 实验版；有阶段控制与算法测试，未完成整局实测 |

原版模式关闭飞行、TP 和服务器指令依赖。控制 Mod 只装客户端，服务器无需安装它。ATM 模式的飞行、地图传送、`/home`、`/back` 等适配，需要世界本身具备对应功能并得到当前使用者授权。

## 工作方式

```text
支持 MCP 的 AI 客户端
        │ stdio
        ▼
Python MCP 服务（mcp_server.py）
        │ 本机认证 HTTP / 127.0.0.1
        ▼
Minecraft 客户端 Mod
        ├─ 读取实际同步状态
        ├─ 在游戏线程执行正常操作
        └─ 返回任务状态与观察结果
```

AI 负责规划和异常处理；客户端逐 tick 控制移动与局部反应。确定的多步流程可以一次提交，减少逐次工具调用的等待。动作仍由游戏和服务器判定，不直接修改服务端方块、物品或玩家能力。

## 能力

- **观察**：角色、背包、当前容器槽位、物品组件、附近实体、已加载区域和游戏界面。
- **批量操作**：移动、采矿、放置、搬运、合成、机器处理及参数化 JSON 工作流。
- **ATM 适配**：JEI 配方索引，部分 AE2、Mekanism、Industrial Foregoing、Pipez、FTB Quests 等接口。
- **连续执行**：有界队列、前后条件检查、失败断点及防重复派发。
- **热更新**：JSON 工作流下次提交即生效；Java 执行模块按任务加载独立 JAR 快照。
- **原版实验流程**：地面探索、资源和装备准备、原版配方书合成、熔炉、弓箭弹道、末影之眼轨迹交会、龙战阶段与步行死亡恢复。

不能读取未打开容器的私有库存，也不能凭任务已提交认定产物已完成。复杂地形、随机掉落、模组特殊机制、延迟和恢复失败仍可能需要人工处理。

## 环境

| 组件 | 要求 |
| --- | --- |
| Minecraft Java | 1.21.1 |
| NeoForge | 21.1.251；声明范围见 Mod 元数据 |
| Java | JDK 21 |
| Python | 3.10+，MCP 服务只使用标准库 |
| JEI | ATM 配方功能需要；原版模式运行时不需要 |
| 开发参考整合包 | ATM10 v8.2 |

维护工具主要面向 Windows。先在独立测试存档验证，不要直接操作重要存档或未经授权的多人设施。

## 构建

源码含 JEI 适配，**编译仍需兼容的 JEI JAR**，但不会把它打包进控制 Mod。可从自己安装的匹配实例中准备编译依赖：

```powershell
python tools/share/prepare_build.py --jei "C:/Instances/ATM10/mods/你的JEI文件.jar"

# 将 JAVA_HOME 指向自己的 JDK 21 后执行
.\gradlew.bat test assemble --max-workers=1
```

Linux/macOS 的 Gradle 入口为 `./gradlew`；部分维护脚本使用 Windows API，并非所有工具都支持其他平台。

```text
build/libs/atm-star-pilot-0.1.0.jar             客户端核心
build/libs/atm-star-pilot-0.1.0-automation.jar  ATM 执行热模块
```

## 安装与 MCP 接入

1. 退出目标实例，将核心 JAR 放入其 `mods/`。
2. 使用 ATM 扩展时，将热模块放到 `config/atmpilot/runtime/automation.jar`，不要放入 `mods/`。原版模式不需要热模块。
3. 将 `server-rules.example.json` 复制到实例的 `config/atmpilot/server-rules.json`。模板默认不授权施工、共享仓储、飞行或旅行；按自己的世界调查并配置。
4. 启动实例。Mod 在实例内生成 `config/atmpilot/session.json`，由本机 MCP 服务读取。
5. 在支持 stdio MCP 的客户端中添加：

```toml
[mcp_servers.minecraft]
command = "python"
args = ["C:/Projects/atm-star-pilot/mcp_server.py", "--session", "C:/Instances/Minecraft/config/atmpilot/session.json"]
startup_timeout_sec = 15
tool_timeout_sec = 60
```

将路径改成自己的绝对路径；Python 不在 PATH 时，`command` 也应填写完整路径。会话文件含本机认证令牌，**不要上传或发送给别人**。

进入世界后先读状态、库存及相关区域/配方，再提交动作。**F7** 打开控制面板，**F8** 紧急停止。默认人工输入优先；持续自动控制必须由当前使用者明确授权。启动游戏不会自动开始建设或通关。

## 常用 MCP 工具

| 工具 | 用途 |
| --- | --- |
| `minecraft_status` / `minecraft_live` | 状态、位置、生命值与附近实体 |
| `minecraft_inventory` / `minecraft_region` | 当前槽位和已加载区域 |
| `minecraft_recipes` / `minecraft_recipe` | JEI 配方查询 |
| `minecraft_workflow` / `minecraft_job` | 批量动作与执行状态 |
| `minecraft_workflows` / `minecraft_run_workflow` | 预览、运行外部 JSON 工作流 |
| `minecraft_stop` | 停止队列、释放按键 |
| `minecraft_vanilla_status` / `minecraft_vanilla_start` | 原版模式状态、启动或显式恢复 |
| `minecraft_vanilla_stop` / `minecraft_vanilla_leave` | 暂停或退出原版模式 |

最小工作流示例，作为 `minecraft_workflow` 的参数：

```json
{
  "steps": [
    {"type": "wait", "ticks": 20}
  ]
}
```

涉及坐标、物品、槽位和配方时必须使用当前观察。断线、超时、光标残留或批次部分完成后，先检查现场，不要盲目重放。

## 原版模式

使用独立的 NeoForge 实例和新生存档，不在 ATM10 世界开启。首次启动要求空物品栏、普通生存模式、非和平难度和原版内容。读取 `minecraft_vanilla_status` 的 `worldKey`，再向 `minecraft_vanilla_start` 传入：

```json
{
  "confirmWorld": "当前status返回的完整worldKey",
  "allowWorldChanges": true,
  "resume": false
}
```

显式恢复使用 `resume: true`。断点按世界和玩家隔离；本模式下人工键鼠输入与 F8 会暂停。详细安装、CLI 入口及限制见 [VANILLA_MODE.zh-CN.md](VANILLA_MODE.zh-CN.md)。

**尚未验证任意新档或任意种子自动通关。** 地形、要塞搜索、高塔水晶和龙战仍待实测；阶段计划、算法测试、编译成功都不是通关证明。

## 测试

```powershell
.\gradlew.bat test --max-workers=1
python -m unittest test_mcp_server test_workflow_store test_work_queue test_stage_core test_vanilla_mode -v
```

覆盖材料规划、动作策略、队列恢复、世界授权、跨维度路径、末影之眼几何、弹道、MCP 协议和安装隔离；不替代完整游戏测试。

## 目录

```text
src/main/          客户端核心、HTTP、原版模式
src/automation/    ATM 热加载执行模块
src/test/          Java 测试
mcp_server.py      stdio MCP 服务
work_queue.py      本地持续任务调度
workflows/         JSON 工作流示例
tools/             构建、部署、打包和维护
skills/            可选的智能体技能
```

ATM 配置边界见 [SHARE_RULES_GUIDE.zh-CN.md](SHARE_RULES_GUIDE.zh-CN.md)。开发先读 [AGENTS.md](AGENTS.md)。核心结构变化通常需要正常重启游戏；热模块更新不代表任意核心改动都能免重启。

## 许可

[MIT](LICENSE)。不包含 Minecraft、ATM10 整合包、JEI 或其他模组的二进制；Gradle Wrapper 使用其自身许可。整合包参考数据仅辅助查询，实际游戏和服务端配方优先。
