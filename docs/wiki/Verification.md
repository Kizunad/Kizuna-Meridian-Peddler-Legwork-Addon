# 验证

自动检查覆盖：

- `scripts/check_data.py` 校验正式元数据无入口、硬依赖齐全、唯一目录路径、七种伤害类型、`kind/blunt`/`melee`/`leg`/`aim/*`/`off_hand`/`two_handed`/`bypasses_shield` 标签和值；
- JUnit 通过功法库真实文本解析器和 Human 导出模板，检查一书三卷十式、按卷前置、书级腿法规则、六肌做功分摊、伤害 ID、腿选择、承接、不留行曲线、蹲马步训练做功和发布 JAR 无 Java 类；
- GameTest 使用正式 Fabric 发现路径和内存人体宿主，验证三卷按顺序学习、持物无关与着地/鞋规则、腿击目标与伤害声明、不留行承接和曲线、蹲马步站定训练；
- `PeddlerScrollDropGameTest` 用内存宿主让三只原版流浪商人分别会三卷、只会上卷、一卷不会，各按原版掉落表抽 200 次玩家击杀：会的卷各掉 25～75 次（0.25 的正负四个标准差），不会的卷一次不掉，不是玩家打死的不掉；JUnit 另查三卷各只向流浪商人声明一项 0.25 的掉落；
- `clienttest`：真客户端连拍，主要给人看，只有飞踹两段判对错。`kz client <本仓> airborne` 录「拳脚不要求着地」：玩家学了袖手拳上卷和千里腿，起跳后、带着缓降从 12 格高处落下时各出一拳（崩拳）、一腿（弹腿），第三人称正面一张接一张拍；最后在地面和半空各放一次飞踹，这两段带断言：地面起手至少跳起半格，半空起手每一张都不比上一张高；截图在运行目录的 `screenshots`，同目录 `airborne.tsv` 记每张的玩家 tick、离地高度、着没着地、起手成没成。一张约隔 3 个游戏 tick，合成录像时按每秒 20/3 张。
- `scripts/check_docs.py` 检查 Wiki 本地链接，`git diff --check` 检查补丁空白。

运行命令（每条 Gradle 命令前先确认没有其他 `gradle-wrapper.jar` 进程）：

```sh
ps -ewwo pid,args | grep '[g]radle-wrapper\\.jar'
bash "gradlew" test --no-daemon
bash "gradlew" build --no-daemon
bash "gradlew" :gametest:runGametest --no-daemon
python3 "scripts/check_docs.py"
python3 "scripts/check_data.py"
git diff --check
```

GameTest 不实现 Damage 或 Human 的第二套执行器，因此真实伤害、靴子耐久扣减、盾牌交互、击退和世界部位命中由上游 Human/Damage 测试覆盖；本仓验证目录提供的参数与标签能被正式服务发现。M3.5 的 20 J/HP、0.5 s 口径下，健康玩家使用铁斧施放终式会超过单臂爆发功率并落到力竭系数；本仓不换武器、不调数值，保留该数值问题供用户决定。
