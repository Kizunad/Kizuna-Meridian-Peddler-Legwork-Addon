"""检查千里腿正式数据包的目录、伤害类型和标签。"""

from pathlib import Path
import json
import sys

NAMESPACE = "meridian_peddler"
DAMAGE_TYPES = {
    "kick_lead_legs", "kick_lead_torso", "kick_lead_head",
    "kick_rear_torso", "kick_rear_head", "kick_double_torso", "axe_kick",
}


def read_json(path: Path, failures: list[str]):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{path}: JSON 无效：{error}")
        return None


def require_tag(tags: dict[str, dict], name: str, expected: set[str], failures: list[str]) -> None:
    tag = tags.get(name)
    if not isinstance(tag, dict):
        failures.append(f"缺少标签：{name}")
        return
    values = tag.get("values")
    if tag.get("replace") is not False or not isinstance(values, list):
        failures.append(f"{name}: 必须有 replace=false 和 values 数组")
        return
    if len(values) != len(set(values)):
        failures.append(f"{name}: values 含重复项")
    actual = set(values)
    if actual != expected:
        failures.append(f"{name}: 实际值与目录不符：{sorted(actual)}")


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    resources = root / "fabric/src/main/resources"
    failures: list[str] = []
    documents = {
        path.relative_to(resources).as_posix(): read_json(path, failures)
        for path in sorted(resources.rglob("*.json"))
    }
    metadata = documents.get("fabric.mod.json")
    if isinstance(metadata, dict):
        if metadata.get("id") != NAMESPACE or metadata.get("environment") != "*":
            failures.append("fabric.mod.json: ID 或环境不属于正式千里腿数据包")
        if metadata.get("entrypoints") or metadata.get("mixins"):
            failures.append("fabric.mod.json: 正式数据包不得声明入口或 mixin")
        dependencies = metadata.get("depends", {})
        for dependency in ("meridian_technique", "meridian_vanilla_damage", "meridian_human_host"):
            if dependency not in dependencies:
                failures.append(f"fabric.mod.json: 缺少硬依赖 {dependency}")
        expected_catalog = f"data/{NAMESPACE}/technique_catalog.txtpb"
        declared = metadata.get("custom", {}).get("meridian_technique:catalogs")
        if declared != expected_catalog:
            failures.append("fabric.mod.json: 必须声明千里腿目录路径")
        test_metadata = read_json(root / "gametest/src/main/resources/fabric.mod.json", failures)
        if isinstance(test_metadata, dict) and test_metadata.get("custom") != metadata.get("custom"):
            failures.append("GameTest 必须使用与正式数据包相同的目录清单声明")
    damage_files = sorted((resources / f"data/{NAMESPACE}/damage_type").glob("*.json"))
    if {path.stem for path in damage_files} != DAMAGE_TYPES:
        failures.append(f"伤害类型应恰为七种，实际为 {sorted(path.stem for path in damage_files)}")
    for name in DAMAGE_TYPES:
        damage = documents.get(f"data/{NAMESPACE}/damage_type/{name}.json")
        if isinstance(damage, dict) and not {"exhaustion", "message_id", "scaling"}.issubset(damage):
            failures.append(f"{name}: 缺少原版伤害类型字段")
    tags: dict[str, dict] = {}
    for path in sorted((resources / "data").rglob("tags/**/*.json")):
        name = path.relative_to(resources / "data").as_posix()
        tag = documents.get(f"data/{name}")
        if isinstance(tag, dict):
            tags[name] = tag
    all_damage = {f"{NAMESPACE}:{name}" for name in DAMAGE_TYPES}
    lead = {f"{NAMESPACE}:kick_lead_legs", f"{NAMESPACE}:kick_lead_torso", f"{NAMESPACE}:kick_lead_head"}
    rear_torso = {f"{NAMESPACE}:kick_rear_torso"}
    rear_head = {f"{NAMESPACE}:kick_rear_head", f"{NAMESPACE}:axe_kick"}
    require_tag(tags, "meridian/tags/damage_type/melee.json", all_damage, failures)
    require_tag(tags, "meridian/tags/damage_type/kind/blunt.json", all_damage, failures)
    require_tag(tags, "meridian/tags/damage_type/leg.json", all_damage, failures)
    require_tag(tags, "meridian/tags/damage_type/off_hand.json", lead, failures)
    require_tag(tags, "meridian/tags/damage_type/two_handed.json", {f"{NAMESPACE}:kick_double_torso"}, failures)
    require_tag(tags, "meridian/tags/damage_type/aim/legs.json", {f"{NAMESPACE}:kick_lead_legs"}, failures)
    require_tag(tags, "meridian/tags/damage_type/aim/torso.json",
                {f"{NAMESPACE}:kick_lead_torso", f"{NAMESPACE}:kick_rear_torso", f"{NAMESPACE}:kick_double_torso"}, failures)
    require_tag(tags, "meridian/tags/damage_type/aim/head.json", rear_head | {f"{NAMESPACE}:kick_lead_head"}, failures)
    require_tag(tags, "meridian/tags/damage_type/aim/arms.json", set(), failures)
    require_tag(tags, "meridian/tags/damage_type/aim/other.json", set(), failures)
    require_tag(tags, "minecraft/tags/damage_type/bypasses_shield.json", {f"{NAMESPACE}:axe_kick"}, failures)
    catalog = root / f"shared/src/main/resources/data/{NAMESPACE}/technique_catalog.txtpb"
    sources = list((root / "shared/src/main/resources").rglob("*.txtpb"))
    if not catalog.is_file() or sources != [catalog]:
        failures.append("功法 txtpb 必须只有 shared 中这一份千里腿目录")
    elif "protected" in catalog.read_text(encoding="utf-8"):
        failures.append("首版目录不得定义 protected 物品标签")
    if failures:
        print("数据检查失败：\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"数据检查通过：{len(documents)} 个 JSON、七种伤害类型和五组 aim 标签。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
