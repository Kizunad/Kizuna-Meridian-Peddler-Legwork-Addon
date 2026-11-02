# 千里腿

本仓提供 `meridian_peddler` 命名空间下的一书三卷九式与蹲马步目录，以及 Minecraft 26.2
伤害类型和数据标签。正式 Fabric 包不包含 Java 运行时代码，也不负责施放、伤害或移动规则；这些行为由
功法库、Damage、Human 与宿主实现。

目录维护源只有一份：[technique_catalog.txtpb](shared/src/main/resources/data/meridian_peddler/technique_catalog.txtpb)。
数据结构和接口边界见 [数据目录](docs/wiki/Home.md)、[上游接口](docs/wiki/Integration.md) 和
[验证记录](docs/wiki/Verification.md)。

本地相邻工作区提供 Framework、Technique、Human 与 Damage 依赖。完整检查：

```sh
bash "gradlew" test --no-daemon
bash "gradlew" build --no-daemon
bash "gradlew" :gametest:runGametest --no-daemon
```

正式包通过 Fabric 元数据声明目录，由功法库在服务端启动时发现、解析并合并校验。
