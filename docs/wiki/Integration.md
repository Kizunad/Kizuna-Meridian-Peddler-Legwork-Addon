# 接口与依赖

## 数据边界

唯一目录是 [`shared/src/main/resources/data/meridian_peddler/technique_catalog.txtpb`](../../shared/src/main/resources/data/meridian_peddler/technique_catalog.txtpb)。正式 Fabric 包只打包数据，`fabric.mod.json` 没有入口；功法库从 `custom.meridian_technique:catalogs` 发现目录并在服务器启动时合并验证。

正式包硬依赖 `meridian_technique`、`meridian_vanilla_damage` 和 `meridian_human_host`。目录绑定 `human:adult@1`，所有腿击绑定 `human:leg_muscle` 六块组织，足经槽绑定 `human:foot_meridian`。验证模块使用 Human 导出的模板资源，不复制协议源码。

## 书卷与目录

书 ID 为 `meridian_peddler:legwork`，卷 ID 为 `road`、`ambush`、`journey`。中卷要求上卷，下卷要求中卷；上卷一次学会三式和蹲马步。所有式的效果表现 ID 与动作规格一致：`meridian_peddler:peddler_<technique>`。

书级 `LegRules` 固定：忽略手持物品、要求着地、基础伤害 2 DAMAGE_POINT、每点靴子护甲增加 0.5 DAMAGE_POINT、每式最后一击磨鞋 1 点耐久。每式腿击的做功功率是设计稿两腿合计值除以六，写入每块肌肉的 `WorkCost`；蹲马步每块肌肉为 200 N、150 W、30 s，并带 `training: true`，移动倍率为 0。

## 伤害类型和标签

七种伤害类型均登记到 `#meridian:kind/blunt`、`#meridian:melee`、`#meridian:leg`。前腿类型另进 `#meridian:off_hand`，双腿类型进 `#meridian:two_handed`；部位使用 `#meridian:aim/legs`、`#meridian:aim/torso`、`#meridian:aim/head`，并提供完整五组 aim 标签。下劈另进原版 `#minecraft:bypasses_shield`。伤害类型 ID 在目录中直接写完整 ID，由功法库和 Human 下游读取。

## 承接与动画差异

承接边为：弹腿→鞭腿、弹腿→连环、扫堂→下劈、尥蹶→扫堂、鞭腿→不留行、飞踹→不留行；不留行只能承接。设计稿 §4.2 曾列出正蹬→飞踹，但动画规格明确写为「正蹬不作后式」并删除入口，本仓按动画规格执行。

不留行的 `tissue_modifier` 从 2800 ms 起，曲线相对点为 0、150、950、1750、9000、11000 ms，力量与爆发峰值为 1.4。承接播放时刻由目录 `start_millis` 声明，宿主不按式名推导。
