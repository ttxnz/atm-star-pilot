# 配置接收者自己的世界

分享包的 `server-rules.example.json` 是**未授权任何施工区域**的模板。复制为实例的 `config/atmpilot/server-rules.json`；不要从别人世界复制坐标、UUID、会话文件或全自动控制授权。

1. 先调查世界与领地，记录共同基地、他人建筑、仓储和机器。区块属于同一队伍、机器允许交互，不等于允许改造。
2. 在与共同基地隔开数个区块、确认没有他人设施或领地的空地，从零规划独立产线。地下、上空和自动采掘/建造机器的完整工作范围也要调查。
3. 只有得到该使用者授权，才添加 `buildAreas`。每项需要自己的 `dimension`、整数 `minX/maxX/minY/maxY/minZ/maxZ`，并在真实调查和领地核对完成后设置 `surveyed=true`、`claimsChecked=true`。空数组会拒绝施工、挖掘和配置。
4. 共同基地保护信息通过 `sharedBase.dimension` 和 `sharedBase.conservativeBounds` 记录。`allowSharedStorageWithdraw` 默认 `false`；只有仓储所有者允许取料后才设置为 `true`。仅允许正常取料与归还多余物品，不允许改机器侧面、管线、能源、样板或升级。打开共同仓储时双手空且不潜行。
5. `allowExistingSurvivalFlight` 只表示允许操控**已经正常解锁**的飞行能力，不会授予飞行。接收者需要确认自己的装备、切换键和有效落点；原实例的双击空格不能直接视为所有实例都适用。
6. `verifiedTransportSites` 默认空。只记录使用者确认已经有出口的现成传送台；具体字段由 `TransportRules.java` 与 `PadTravel.java` 定义。没有核实出口的台不要自动使用。
7. `survivalCommands.allow` 默认空；只有服务器确实提供、且使用者授权时，才逐项加入 `home`、`back`。命令冷却和落点会变化，不要连续重试结果未明的 `/back`。
8. `deathRecovery.enabled` 和 `survivalGuard.enabled` 默认 `false`。启用前检查当前代码需要的策略字段、自己的墓碑行为、回家方式、飞行和安全落点。只恢复自己的物品，保留检查点，限制重试；恢复后按真实库存和现场重规划。
9. `manualControlPolicy.mode` 默认 `manual_input_priority`。键鼠操作、F7 控制面板、F8 紧急停止与明确停止优先。只有当前使用者明确授权持续全自动时，才考虑改为 `autonomous_until_explicit_stop`；F8 和明确停止仍优先。
10. `allowNativeMapTeleport` 默认 false，`verifiedWaypoints` 默认空。启用要有当前用户授权和真实原生地图权限。新路径点应有实时加载、净空、干燥的观察；悬停点要求现有飞行。`travelAccessOnly=true` 只允许交通，不能据此施工或采掘。
11. `verifiedSurvivalPortals` 默认空。现成下界门记录 name、dimension、entry、stand、targetDimension、observedExisting；由 PortalTravel 复核门方块和安全站位，正常穿门并核对到达。不创建、修改门框或调用服务端建门函数。
12. 常驻队列只执行当前世界具体已规划步骤；不要从别人实例复制 work-queue.json 或检查点。机器、菜单、状态和部分投料不同，不能直接重放。

执行前读实际世界、配方、菜单和库存。不要以“测试”“优化”为理由改造共同设施。热加载的新模块也必须遵守相同边界。规则文件不是给智能体绕过权限的凭证；服务端权限始终有效。

`ServerRules.java`、`TransportRules.java`、`SurvivalCommands.java`、`DeathRecovery.java`、`SurvivalReflex.java` 以及运行模块中的相关流程是当前实际读取字段的依据。未配置完整的高级功能保持关闭，先从只读查询与自有区域的明确小流程开始。
