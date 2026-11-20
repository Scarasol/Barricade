# 街垒（Barricade）

本模组添加了具有末世风格的城市临时防御工事和场景照明，适合生存基地、废弃街区和自制地图。

## 主要内容

目前主要添加了三种类型的方块，每一种都有一些特定的功能。

### 街垒

放置街垒时，方块会从默认标签 `barricade:materials` 中挑选随机模型进行填充，堆成一格半高的路障。支持原版模型、GeckoLib 和 Simple Bedrock Model。

手持方块潜行右键街垒，能把方块嵌进去。街垒会保持杂乱的模型状态，嵌入的方块仍然可以正常交互。拆掉嵌入的方块，杂物会原位恢复。目前对于门、床这种多格方块暂不支持。

在铁砧里把街垒命名为标签名称（如 `minecraft:logs`），放置时就会从该标签中挑选模型，玩家可以利用此功能创建不同模型的街垒杂物堆。

`barricade:materials` 标签中包含了 MrCrayfish’s Refurbished Furniture 的家具，和该模组一起安装时，默认模型池会包含该模组的家具。

### 封条

模组增加了木质和铁质的窗户封条，封条会根据玩家放置的区域大小自适应生成模型，避免出现封条千篇一律的情况。封条能挡住通行，但不阻碍弹射物穿过（兼容 TaCZ 或 Superb Warfare）。同时，特定近战武器也能透过封条攻击另一侧的敌人。

可以用标签 `barricade:window_boards_pass_through` 增加能穿过封条的弹射物，或用物品标签 `barricade:window_boards_attack_through` 指定哪些近战武器可以隔着封条命中。搭配 Better Combat，长矛、骑枪和三叉戟类武器可以自动兼容。

### 灯串

把灯串进电盒装在墙上、地上或天花板上，用灯线右键出线口，再右键另一处。灯线会自然生成，一次最远可接 32 格，也可以从已有挂点延长或分支。

灯使用 FE 供能，空手右键进电盒即可开关。可以用剪刀拆除灯线。

**注意：**模组没有增加 FE 设备，玩家需要自己安装 FE 供能模组为灯泡供能。

## 安装要求

需要 **Minecraft Java 版 1.20.1、对应版本的 Forge 47.3.19 或更高版本，以及 Java 17**。客户端必装 [Accelerated Rendering（AR）](https://www.curseforge.com/minecraft/mc-mods/accelerated-rendering/files/8448250) 1.0.14 或更高版本（1.20.1 Forge）；专用服务端不需要安装 AR。客户端显卡和驱动需支持 OpenGL 4.3 和 64 位着色器整数。

文中提到的其他联动模组均为可选。

作者：**Scarasol** · 许可证：**GNU Affero General Public License v3.0**
