# 接口与依赖

## 数据边界

唯一目录是 [`shared/src/main/resources/data/meridian_peddler/technique_catalog.txtpb`](../../shared/src/main/resources/data/meridian_peddler/technique_catalog.txtpb)。功法库从 `custom.meridian_technique:catalogs` 发现目录并在服务器启动时合并验证。

正式包硬依赖 `meridian_technique`、`meridian_vanilla_damage` 、`meridian_human_host` 和 `meridian_currency`。目录绑定 `human:adult@1`，所有腿击绑定 `human:leg_muscle` 六块组织，足经槽绑定 `human:foot_meridian`。验证模块使用 Human 导出的模板资源，不复制协议源码。

## 行脚货郎

主世界每 100 游戏刻寻找一次落脚机会，服从自然刷怪和流浪商人生成规则。从在世且非旁观的玩家中任选一位，在其附近已加载的地表开担。首次找到合适落脚点即可到访，以后每隔 48,000 游戏刻可开新担，每趟停 12,000 游戏刻。两次到访间留出赶路时间。区块重载用实体保存的离场时刻还原倒计时，睡觉与调日照不推进到访时钟。

游戏规则 `meridian_peddler:natural_visits` 控制新到访，生存世界默认开启。可选数据包 `meridian_peddler:no_natural_visits` 在加载时将该规则设为关闭，已到访的货郎按原定时间离开。专服 GameTest 自动加载所有可用数据包，只有货郎到访用例所在的独立测试环境临时开启规则。Fabric 客户端测试的新世界默认关闭 `spawn_mobs`，交易场景自行开启它。

蓝袍货郎孙快腿手持收纳袋，普通主手右键打开交易窗口。三卷、火把、面包的占位售价分别是 120、240、480、3、12 文，皮革收购价 35 文，最多收 16 张。价格、量词和收购上限在[货单](../../fabric/src/main/resources/data/meridian_peddler/merchant_offers/pack.json)，各卷和零钱的初装数量在[装担表](../../fabric/src/main/resources/data/meridian_peddler/loot_table/pack.json)。

新到访只抽一次装担表。演员的 UUID 就是本趟钱匣号，Currency 保存实际的货、钱和回执；同一趟所有玩家共摊。离场时货郎带走余货与收付所得，旧账保留在存档中，下一趟使用新钱匣。

## 书卷与目录

生存来源：三卷都声明了 `scroll_drops { entity_type: "minecraft:wandering_trader" chance: 0.25 }`，功法库据此往原版流浪商人掉落表各加一池。玩家亲手杀死、且死者会这一整卷时按 0.25 掉一卷，不是玩家打死的不掉。死者会哪几卷由 Threatening 学卷表决定：游炁、真炁流浪商人都会上卷，只有真炁会中卷，一成真炁会下卷。0.25 是占位概率。

书 ID 为 `meridian_peddler:legwork`，卷 ID 为 `road`、`ambush`、`journey`。中卷要求上卷，下卷要求中卷；上卷一次学会三式和蹲马步。所有式的效果表现 ID 与动作规格一致：`meridian_peddler:peddler_<technique>`。

书级 `LegRules` 固定：忽略手持物品、不要求着地（空中、水里、攀爬、骑乘、滑翔时都能出腿，用户 11-15 定；飞踹 1350 ms 的向上冲量到那一刻不站在地上就不给，只给水平那份，由功法库统一处理）、基础伤害 2 DAMAGE_POINT、每点靴子护甲增加 0.5 DAMAGE_POINT、每式最后一击磨鞋 1 点耐久。每式腿击的做功功率是设计稿两腿合计值除以六，写入每块肌肉的 `WorkCost`；蹲马步每块肌肉为 200 N、150 W、30 s，并带 `training: true`，移动倍率为 0。

## 伤害类型和标签

七种伤害类型均登记到 `#meridian:kind/blunt`、`#meridian:melee`、`#meridian:leg`。前腿类型另进 `#meridian:off_hand`，双腿类型进 `#meridian:two_handed`；部位使用 `#meridian:aim/legs`、`#meridian:aim/torso`、`#meridian:aim/head`，并提供完整五组 aim 标签。下劈另进原版 `#minecraft:bypasses_shield`。伤害类型 ID 在目录中直接写完整 ID，由功法库和 Human 下游读取。

## 承接与动画差异

承接边为：弹腿→鞭腿、弹腿→连环、扫堂→下劈、尥蹶→扫堂、鞭腿→不留行、飞踹→不留行；不留行只能承接。设计稿 §4.2 曾列出正蹬→飞踹，但动画规格明确写为「正蹬不作后式」并删除入口，本仓按动画规格执行。

不留行的 `tissue_modifier` 从 2800 ms 起，曲线相对点为 0、150、950、1750、9000、11000 ms，力量与爆发峰值为 1.4。承接播放时刻由目录 `start_millis` 声明，宿主不按式名推导。
