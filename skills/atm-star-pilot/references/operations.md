# 执行、旅行与断点

优先使用已连接的 Minecraft MCP。底层 mcp_server.Bridge 只用于本项目尚未暴露的受限接口；读取本机会话路径，不打印或复制令牌。读取可与文件分析并行，改变人物、菜单、机器和库存的动作按依赖顺序执行。

## 批量和常驻队列

一次工作流支持 1..4096 个动作。craft.count 和 collect_output.count 是额外产出数量，不是最终总库存。工作流提交时冻结，之后修改文件不改变当前任务。

从项目根启动常驻协调器：

```powershell
python tools/run_queue.py --session "<instance>/config/atmpilot/session.json"
```

计划和检查点默认为同目录 work-queue.json / work-queue-state.json。计划含 enabled、generation、afterJob 和 stages；阶段含唯一 id、name、before、具体 steps、after。条件可检查 dimension、menuId、menuClass、screenClass、minHealth、position、无额外组件的 items / itemsAtMost。

- afterJob 来自实际读取的原生基线；只有其成功完成且现场条件满足，才派发下一段。
- 同 generation 仅原子追加新 id，不删除或改写已接受阶段。用项目 work_queue.save_json 保存，避免部分写入。
- 派发意图先保存；inflight.job 为空意味着结果不确定，不自动重发 POST。
- needs_replan、其他 job 接管、死亡、停止、光标非空或条件变化时，读取检查点与现场。确需新 generation 时核对并停止自己的协调进程，归档旧计划与检查点，再基于新确认基线启动。不杀其他进程。
- 分析前可追加下一批已核实工作；尚未观察的菜单、配方与状态不能用猜测步骤占位。

## 旅行和放置

extension:fly 的 x/y/z 为整数方块坐标，不能传半格小数。非直达路线的 cruiseY 不低于当前位置，至少高于目标 Y 三格，并留意世界顶与全程净空。

hover=true 用于悬停；落地应有真实支撑，用正常落地模式。游戏可能在落地时自动关闭飞行。已有飞行用正常双击 Space 操控，不能写能力标志。

人物不要占据放置格。水平机器尽量平视放置；俯仰可能使钻机朝上／朝下。读取真实 blockstate、facing 和 workingArea，不能仅从 yaw 推断成功。拆回机器后用 minecraft_scan 找 ItemEntity 的真实位置，掉落物可能滑落到地面。

地图 TP 检查真实权限、原生已保存路径点、飞行和到达区块。新 travelAccessOnly 点必须当前已加载、干燥、净空；它不进入 buildAreas。

extension:portal_travel(name) 正常走入 verifiedSurvivalPortals 中的现成下界门，保留正常跨维度交通标记。extension:pad_travel 是不同流程：双手空、落地、持续 Shift 至真实潜行稳定 30 ticks，右键一次并保持到确认到达。

末地低层跨维度旅行先确认区块加载并保留飞行；必要时先到安全高点，再维度内短途旅行。历史安全记录不代替本次加载。挂机时可打开正常物品栏，防止闲置悬停离开刷怪距离，但仍需读实际位置。

## 菜单与失败

menuId 和槽位随菜单改变，普通箱子、背包、AE 与 Titanium 索引不同。中断后重新观察，不能用旧槽位继续投料。

光标非空时不关菜单、不丢物品、不派发后续工作。停下后读取精确 item/count 和同菜单的空玩家槽，minecraft_return_cursor 归位后再检查。

自有物理 AE 终端且玩家槽全满时，受限 cursor/return 腾槽接口只允许一次 QUICK_MOVE 回存已观察可堆叠材料。核对来源精确 item/count、确认槽空后才归位光标。不能用于公共配置槽或装备。平时用 extension:ae_deposit(items:[...]) 回存暂不用的材料，保留组件并预留产物槽。

最新 job=completed 可能只是安全返回完成。切维度、死亡或撤离后核对 job id 和事件记录，确认原生产做了哪些，再规划剩余部分。
