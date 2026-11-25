package dev.kizuna.meridian.peddler.verification;

import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ScrollDrop;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 千里腿目录的结构、数值占位和上游接口回归。 */
public final class PeddlerCatalogTest {
    private static final String NAMESPACE = "meridian_peddler";
    private static final String BOOK_ID = NAMESPACE + ":legwork";
    private static final String DROPPED_BY = "minecraft:wandering_trader";
    private static final double SCROLL_DROP_CHANCE = 0.25;
    private static final double EPSILON = 0.000001;
    private static final int LEG_COUNT = 6;
    private static final int TECHNIQUE_COUNT = 10;
    private static final int VOLUME_COUNT = 3;
    private static final double MOVEMENT_SPEED = 0.4;
    private static final double BASE_DAMAGE = 2.0;
    private static final double ARMOR_DAMAGE = 0.5;
    private static final int FOLLOW_UP_COUNT = 2;
    private static final long TISSUE_MODIFIER_START_MILLIS = 2_800;
    private static final List<Long> CURVE_OFFSETS_MILLIS = List.of(0L, 150L, 950L, 1_750L, 9_000L, 11_000L);
    private static final double HORSE_STANCE_FORCE = 200.0;
    private static final double HORSE_STANCE_POWER = 150.0;
    private static final double HORSE_STANCE_DURATION = 30.0;
    private static final long HORSE_STANCE_WARMUP_MILLIS = 31_000;
    private static final long HORSE_STANCE_RECOVERY_MILLIS = 1_000;
    private static final long HORSE_STANCE_COOLDOWN_MILLIS = 43_000;
    private static final Map<String, Double> POWERS = Map.of(
            "snap_kick", 66.66666666666667,
            "push_kick", 100.0,
            "ground_sweep", 116.66666666666667,
            "roundhouse", 150.0,
            "triple_kick", 133.33333333333333,
            "mule_kick", 166.66666666666666,
            "flying_kick", 166.66666666666666,
            "axe_kick", 150.0,
            "whirlwind", 166.66666666666666);
    private static final Map<String, Integer> STRIKE_COUNTS = Map.of(
            "snap_kick", 1, "push_kick", 1, "ground_sweep", 2,
            "roundhouse", 1, "triple_kick", 3, "mule_kick", 1,
            "flying_kick", 1, "axe_kick", 1, "whirlwind", 2);

    @Test
    public void catalogHasOneBookThreeVolumesAndTenDefinitions() throws IOException {
        // 书、卷和式的身份必须由正式目录解析器完整收集。
        TechniqueRegistry registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        assertEquals(1, registry.books().size());
        assertEquals(BOOK_ID, registry.books().get(0).getId());
        assertEquals(VOLUME_COUNT, registry.volumes().size());
        assertEquals(TECHNIQUE_COUNT, registry.definitions().size());
        var road = registry.findVolume("meridian_peddler:road").orElseThrow();
        var ambush = registry.findVolume("meridian_peddler:ambush").orElseThrow();
        var journey = registry.findVolume("meridian_peddler:journey").orElseThrow();
        assertEquals("meridian_peddler:road", road.getId());
        assertEquals("meridian_peddler:ambush", ambush.getId());
        assertEquals("meridian_peddler:journey", journey.getId());
        assertEquals("meridian_peddler:road", ambush.getPrerequisiteVolumeIds(0));
        assertEquals("meridian_peddler:ambush", journey.getPrerequisiteVolumeIds(0));
    }

    @Test
    public void bookRulesIgnoreHeldItemsAllowAirborneAndChargeBoots() throws IOException {
        // 书级腿法规则固定持物、着地和鞋耐久口径，宿主不能猜默认值；用户 11-15 定腿法不要求着地，空中也能出腿。
        var book = PeddlerCatalog.registry(PeddlerCatalog.load()).books().get(0);
        var rules = book.getLegRules();
        assertTrue(rules.getIgnoreHeldItems());
        assertTrue(rules.hasRequireGrounded());
        assertFalse(rules.getRequireGrounded());
        assertEquals(BASE_DAMAGE, rules.getBaseDamage().getValue(), 0);
        assertEquals(ARMOR_DAMAGE, rules.getArmorDamage().getValue(), 0);
        assertEquals(1, rules.getDurabilityCost());
    }

    @Test
    public void everyStrikeBindsSixLegMusclesAndKeepsPerMusclePower() throws IOException {
        // 两腿六块肌肉的合计做功在目录中平均分摊，避免把总功率重复扣六次。
        TechniqueRegistry registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        for (var entry : POWERS.entrySet()) {
            TechniqueDefinition definition = registry.findLatest(NAMESPACE + ":" + entry.getKey()).orElseThrow();
            var legSlot = definition.getSlotsList().stream()
                    .filter(slot -> slot.getId().equals("leg_muscles")).findFirst().orElseThrow();
            assertEquals(LEG_COUNT, legSlot.getMinimumCount());
            assertEquals(LEG_COUNT, legSlot.getMaximumCount());
            var work = definition.getCost().getWork(0);
            assertEquals(entry.getValue(), work.getPower().getValue(), EPSILON);
            assertEquals(LEG_COUNT * entry.getValue(), work.getPower().getValue() * LEG_COUNT, EPSILON);
            long strikeCount = definition.getEffectsList().stream()
                    .filter(TechniqueEffect::hasLegStrike).count();
            assertEquals(STRIKE_COUNTS.get(entry.getKey()).intValue(), strikeCount);
        }
    }

    @Test
    public void strikesUseDeclaredDamageIdsAndLegSelections() throws IOException {
        // 伤害身份由目录直接提供，前后腿和双腿选择与书的动作表一一对应。
        TechniqueRegistry registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        assertStrike(registry, "snap_kick", "kick_lead_legs", LegSelection.FRONT_LEG);
        assertStrike(registry, "push_kick", "kick_lead_torso", LegSelection.FRONT_LEG);
        assertStrike(registry, "roundhouse", "kick_rear_head", LegSelection.REAR_LEG);
        assertStrike(registry, "mule_kick", "kick_double_torso", LegSelection.BOTH_LEGS);
        assertStrike(registry, "axe_kick", "axe_kick", LegSelection.REAR_LEG);
        assertStrike(registry, "whirlwind", "kick_rear_head", LegSelection.REAR_LEG);
    }

    @Test
    public void followUpsAndWhirlwindCurveMatchAnimationTimeline() throws IOException {
        // 承接只保留动作规格中的边；不留行曲线从起跳开始，且在冷却边界前结束。
        TechniqueRegistry registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        var whirlwind = registry.findLatest(NAMESPACE + ":whirlwind").orElseThrow();
        assertTrue(whirlwind.getFollowUp().getOnlyFollowUp());
        assertEquals(FOLLOW_UP_COUNT, whirlwind.getFollowUp().getTransitionsCount());
        assertEquals(NAMESPACE + ":roundhouse", whirlwind.getFollowUp().getTransitions(0).getPrevious().getId());
        assertEquals(NAMESPACE + ":flying_kick", whirlwind.getFollowUp().getTransitions(1).getPrevious().getId());
        var modifier = whirlwind.getEffectsList().stream()
                .filter(TechniqueEffect::hasTissueModifier).findFirst().orElseThrow().getTissueModifier();
        assertEquals(CURVE_OFFSETS_MILLIS,
                modifier.getCurveList().stream().map(point -> point.getOffsetMillis()).toList());
        long castEnd = whirlwind.getPhases().getWarmupMillis()
                + whirlwind.getPhases().getActiveMillis()
                + whirlwind.getPhases().getRecoveryMillis();
        long curveEnd = TISSUE_MODIFIER_START_MILLIS
                + modifier.getCurve(modifier.getCurveCount() - 1).getOffsetMillis();
        assertTrue(curveEnd <= castEnd + whirlwind.getCooldownMillis());
        assertFalse(registry.findLatest(NAMESPACE + ":flying_kick").orElseThrow().getFollowUp()
                .getTransitionsList().stream().anyMatch(transition ->
                        transition.getPrevious().getId().equals(NAMESPACE + ":push_kick")));
    }

    @Test
    public void horseStanceIsTrainableStationaryAndCostsNoQi() throws IOException {
        // 蹲马步只做训练负荷，站定且无经脉扣费，随上卷学习。
        TechniqueRegistry registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        var stance = registry.findLatest(NAMESPACE + ":horse_stance").orElseThrow();
        assertEquals(0, stance.getCost().getQiCount());
        assertEquals(1, stance.getCost().getWorkCount());
        assertTrue(stance.getCost().getWork(0).getTraining());
        assertEquals(HORSE_STANCE_FORCE, stance.getCost().getWork(0).getForce().getValue(), 0);
        assertEquals(HORSE_STANCE_POWER, stance.getCost().getWork(0).getPower().getValue(), 0);
        assertEquals(HORSE_STANCE_DURATION, stance.getCost().getWork(0).getDuration().getValue(), 0);
        assertEquals(0.0, stance.getMovementSpeedMultiplier(), 0);
        assertEquals(HORSE_STANCE_WARMUP_MILLIS, stance.getPhases().getWarmupMillis());
        assertEquals(HORSE_STANCE_RECOVERY_MILLIS, stance.getPhases().getRecoveryMillis());
        assertEquals(HORSE_STANCE_COOLDOWN_MILLIS, stance.getCooldownMillis());
        assertEquals(0.0, stance.getAiHints().getCategoryWeight().getValue(), 0);
    }

    @Test
    public void productionJarContainsDeclaredCatalogAndPeddler() throws IOException {
        // 发行包应同时带有功法目录、货郎入口和开摊数据。
        try (var jar = new JarFile(System.getProperty("peddlerLegwork.fabricJar"))) {
            String path = PeddlerCatalog.RESOURCE.substring(1);
            String metadata = jarText(jar, "fabric.mod.json");
            assertTrue(metadata.contains("\"meridian_technique:catalogs\": \"" + path + "\""));
            assertTrue(metadata.contains("dev.kizuna.meridian.peddler.PeddlerVisits"));
            assertNotNull(jar.getJarEntry("dev/kizuna/meridian/peddler/PeddlerVisits.class"));
            assertNotNull(jar.getJarEntry("data/meridian_peddler/merchant_offers/pack.json"));
            assertNotNull(jar.getJarEntry("data/meridian_peddler/loot_table/pack.json"));
            assertNotNull(jar.getJarEntry(path));
        }
    }

    private static void assertStrike(TechniqueRegistry registry, String id, String damage, LegSelection leg)
            throws IOException {
        // 单体腿击的伤害 ID 和出力腿必须是目录声明，不由宿主按式名推导。
        var definition = registry.findLatest(NAMESPACE + ":" + id).orElseThrow();
        var strike = definition.getEffectsList().stream().filter(TechniqueEffect::hasLegStrike)
                .findFirst().orElseThrow().getLegStrike();
        assertEquals(NAMESPACE + ":" + damage, strike.getDamageTypeId());
        assertEquals(leg, strike.getLeg());
        assertEquals(MOVEMENT_SPEED, definition.getMovementSpeedMultiplier(), EPSILON);
    }

    private static String jarText(JarFile jar, String path) throws IOException {
        // 缺失资源直接指出发布包内的路径，避免把空内容当成通过。
        var entry = jar.getJarEntry(path);
        if (entry == null) {
            throw new IOException("发布包缺少资源：" + path);
        }
        try (var stream = jar.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void everyVolumeDropsOnlyFromWanderingTradersKilledByPlayers() throws IOException {
        // 生存来源：三卷都只向流浪商人声明一项，概率占位；死者会不会这一卷由 Threatening 学卷表决定。
        TechniqueRegistry registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        ScrollDrop drop = ScrollDrop.newBuilder()
                .setEntityType(DROPPED_BY)
                .setChance(SCROLL_DROP_CHANCE)
                .build();
        for (var volume : registry.volumes()) {
            assertEquals(volume.getId(), List.of(drop), volume.getScrollDropsList());
        }
    }
}
