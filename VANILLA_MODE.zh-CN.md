# 原版生存自动通关模式（实验版 1）

目标版本：Minecraft Java 1.21.1，客户端 NeoForge 21.1.251 与本控制 Mod。服务器不需要安装控制 Mod。JEI 现为可选；本模式使用客户端同步的原版配方和正常生存交互。

**代码已提供自动通关流程，但尚未完成新档从零击杀末影龙的端到端验证。请按实验版使用，不能把编译和单元测试通过当作已经自动通关。**

## 独立安装

1. 在启动器新建一个上述版本的 NeoForge 实例，不使用原 ATM10 实例，不添加改变玩法的模组。
2. 关闭该实例，在本包目录运行：

   ```powershell
   python tools/install_vanilla.py --instance "你的独立实例目录"
   ```

3. 启动它，新建普通生存存档（非和平、非创造、非极限），初始物品栏为空。不要给物品、开启飞行或使用传送。
4. MCP 启动配置：

   ```toml
   [mcp_servers.minecraft]
   command = "python"
   args = ["本包目录/mcp_server.py", "--session", "独立实例目录/config/atmpilot/session.json"]
   ```

本包只安装控制核心；原版模式不需要 automation.jar、JEI、FTB、背包或墓碑。Python 3.10+，无第三方 Python 依赖。不要把旧世界的 server-rules、会话令牌、断点或权限复制过来。

## 启动与恢复

- `minecraft_vanilla_status`：读取当前世界的 `worldKey`、阶段、原因和活动任务。
- `minecraft_vanilla_start`：传 `confirmWorld` 为上述完整 worldKey，`allowWorldChanges: true`。只在明确交付自动游玩的独立世界调用。
- `minecraft_vanilla_stop` 或 **F8**：暂停并释放按键。
- 人工键鼠输入也会暂停这个模式；再次启动传 `resume: true`，从实际库存/现场重新规划，不重放未确认点击。
- `minecraft_vanilla_leave`：退出模式，才允许切回原 ATM 工作流。不会自动启动任何 ATM 操作。
- 新版 MCP 工具未刷新时可使用：

  ```powershell
  python tools/vanilla_runner.py --session "独立实例/config/atmpilot/session.json" status
  python tools/vanilla_runner.py --session "独立实例/config/atmpilot/session.json" start --confirm-world "status返回的完整worldKey"
  python tools/vanilla_runner.py --session "独立实例/config/atmpilot/session.json" resume --confirm-world "同一worldKey"
  python tools/vanilla_runner.py --session "独立实例/config/atmpilot/session.json" stop
  ```

## 已实现的流程

- 原版物品栏/配方书合成、工作台、熔炉补料和收取；实际产物计数驱动下一步。
- 原木、石料、铁、钻石等可见资源采集；渐进地面搜索、阶梯掘进、放支撑过短缺口。只读取客户端已加载信息，不读取种子或服务端结构坐标。
- 食物、工具、铁甲、盾、弓箭和下界门准备；观察正常穿门形成的跨维度路径。
- 烈焰人和末影人资源获取；扔末影之眼、记录轨迹、交会估计要塞方向，再寻找实际末地门框。
- 填充门框、拆水晶、弓箭离散弹道/移动预瞄、龙停栖近战；实际观察龙死亡，再进入出口判定通关。
- 本地饥饿、苦力怕、龙息、低血量退避，以及有条件的水桶灭火/缓降；正常重生、沿已观察传送门路径步行寻找掉落点。

断点保存在独立实例的 `config/atmpilot/vanilla/`，按世界与玩家隔离。运行有阶段超时和失败上限，遇到无法确认的状态进入 `needs_attention`，不会持续重放投料。`complete` 必须有已记录的龙死亡依据，不以阶段计划已提交代替通关。

## 验证范围与当前限制

已覆盖纯算法/策略单元测试：末影之眼交会、离散弹道边界、扩张搜索不绕圈、跨维度路径、世界授权与飞行/指令禁用策略；另有 MCP 路由与安装隔离测试。

尚未在独立新档验证的重点是复杂水域和山地、堡垒与要塞搜索效率、高塔铁笼水晶接近、龙战移动及死亡后物资损失。生物掉落有随机性，资源搜索可能耗时或到达失败上限；本版不承诺任意种子一遍通关，不使用瞬移或服务器作弊来跳过这些情况。

源码入口：`src/main/java/dev/atmpilot/vanilla/VanillaRun.java`。当前 ATM 的规则与运行数据保持原位；此包不含存档、账号、会话、坐标或其他模组。
