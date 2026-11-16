package dev.kizuna.meridian.peddler.test;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.peddler.verification.PeddlerCatalog;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostFacts;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueActivityCommit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueHostResponse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueProtocol;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 千里腿整合验证，使用真实目录服务和内存人体宿主。 */
public final class PeddlerGameTest implements TechniqueHostEntrypoint {
    private static final String ROAD_VOLUME = "meridian_peddler:road";
    private static final String AMBUSH_VOLUME = "meridian_peddler:ambush";
    private static final String JOURNEY_VOLUME = "meridian_peddler:journey";
    private static final int ROAD_KNOWN_COUNT = 4;
    private static final int AMBUSH_KNOWN_COUNT = 7;
    private static final int JOURNEY_KNOWN_COUNT = 10;
    private static final int ALL_TECHNIQUE_COUNT = 10;
    private static final int CURVE_POINT_COUNT = 6;
    private static final int STRIKE_DEFINITION_COUNT = 13;
    private static final int FOLLOW_UP_COUNT = 2;
    private static final double BASE_DAMAGE = 2.0;
    private static final double ARMOR_DAMAGE = 0.5;
    private static final double EXHAUSTION_CURVE_PEAK = 1.4;
    private static final Map<UUID, MemoryTechniqueHost> HOSTS = new HashMap<>();

    @GameTest(maxTicks = PeddlerGameTestTicks.DEFAULT_TIMEOUT)
    public void volumesLearnInOrderAndIncludeHorseStance(GameTestHelper context) {
        // 真实 Fabric 服务发现目录，按卷前置依次写入同一身体的功法进度。
        UUID entityId = UUID.randomUUID();
        MemoryTechniqueHost host = registerHost(entityId);
        TechniqueService service = TechniqueFabric.service();
        String expectedSource = "meridian_peddler_test:data/meridian_peddler/technique_catalog.txtpb";
        context.assertTrue(service.registry().sources().stream()
                .anyMatch(source -> source.source().equals(expectedSource)), "千里腿目录没有被发现");
        try {
            rejectVolume(service, entityId, JOURNEY_VOLUME);
            var road = service.learn(entityId, ROAD_VOLUME);
            context.assertTrue(road.size() == ROAD_KNOWN_COUNT, "上卷没有学会三式和蹲马步");
            rejectVolume(service, entityId, JOURNEY_VOLUME);
            var ambush = service.learn(entityId, AMBUSH_VOLUME);
            context.assertTrue(ambush.size() == AMBUSH_KNOWN_COUNT, "中卷学习后已知功法数量不符");
            var journey = service.learn(entityId, JOURNEY_VOLUME);
            context.assertTrue(journey.size() == JOURNEY_KNOWN_COUNT, "下卷学习后已知功法数量不符");
            int knownCount = host.progress().getKnownCount();
            context.assertTrue(knownCount == ALL_TECHNIQUE_COUNT,
                    "按卷学习没有保留全部十式");
        } finally {
            HOSTS.remove(entityId);
        }
        context.succeed();
    }

    @GameTest(maxTicks = PeddlerGameTestTicks.DEFAULT_TIMEOUT)
    public void legBookRulesCoverHeldItemsGroundingAndBoots(GameTestHelper context) throws IOException {
        // 书级事实来自目录，手持物品不改变腿法条件，着地和鞋参数必须显式存在。
        var book = PeddlerCatalog.registry(PeddlerCatalog.load()).books().get(0);
        var rules = book.getLegRules();
        context.assertTrue(rules.getIgnoreHeldItems(), "腿法没有忽略手持物品");
        context.assertTrue(rules.hasRequireGrounded() && !rules.getRequireGrounded(), "腿法应写明不要求着地");
        context.assertTrue(rules.getBaseDamage().getValue() == BASE_DAMAGE, "光脚基础伤害不符");
        context.assertTrue(rules.getArmorDamage().getValue() == ARMOR_DAMAGE, "鞋护甲伤害不符");
        context.assertTrue(rules.getDurabilityCost() == 1, "鞋耐久消耗不符");
        context.succeed();
    }

    @GameTest(maxTicks = PeddlerGameTestTicks.DEFAULT_TIMEOUT)
    public void everyKickCarriesDamagePartAndBootStrikeRules(GameTestHelper context) throws IOException {
        // 每次打击都携带目录直接声明的伤害 ID、出力腿、目标预算和末击磨鞋标记。
        var registry = PeddlerCatalog.registry(PeddlerCatalog.load());
        int definitionsWithStrikes = 0;
        for (TechniqueDefinition definition : registry.definitions()) {
            for (TechniqueEffect effect : definition.getEffectsList()) {
                if (!effect.hasLegStrike()) {
                    continue;
                }
                definitionsWithStrikes++;
                var strike = effect.getLegStrike();
                context.assertTrue(strike.getDamageTypeId().startsWith("meridian_peddler:"),
                        "伤害类型没有使用千里腿命名空间");
                context.assertTrue(strike.getMaximumTargets() > 0, "腿击没有目标上限");
                context.assertTrue(strike.hasLastStrike(), "腿击没有末击标记");
            }
        }
        context.assertTrue(definitionsWithStrikes == STRIKE_DEFINITION_COUNT, "九式多段腿击数量不符");
        context.succeed();
    }

    @GameTest(maxTicks = PeddlerGameTestTicks.DEFAULT_TIMEOUT)
    public void whirlwindHasFollowUpsAndExhaustionCurve(GameTestHelper context) throws IOException {
        // 不留行只能由鞭腿或飞踹承接，曲线覆盖起跳、峰值、虚脱和恢复段。
        var whirlwind = PeddlerCatalog.registry(PeddlerCatalog.load())
                .findLatest("meridian_peddler:whirlwind").orElseThrow();
        context.assertTrue(whirlwind.getFollowUp().getOnlyFollowUp(), "不留行没有限制为承接式");
        context.assertTrue(whirlwind.getFollowUp().getTransitionsCount() == FOLLOW_UP_COUNT,
                "不留行承接入口数量不符");
        var modifier = whirlwind.getEffectsList().stream()
                .filter(TechniqueEffect::hasTissueModifier).findFirst().orElseThrow().getTissueModifier();
        context.assertTrue(modifier.getCurveCount() == CURVE_POINT_COUNT, "不留行曲线点数不符");
        context.assertTrue(modifier.getStrengthPeak().getValue() == EXHAUSTION_CURVE_PEAK,
                "不留行力量峰值不符");
        context.assertTrue(modifier.getBurstPeak().getValue() == EXHAUSTION_CURVE_PEAK,
                "不留行爆发峰值不符");
        context.succeed();
    }

    @GameTest(maxTicks = PeddlerGameTestTicks.DEFAULT_TIMEOUT)
    public void horseStanceIsTrainableAndStationary(GameTestHelper context) throws IOException {
        // 蹲马步是上卷内的训练式，训练做功使用腿部组织并把移动倍率降为零。
        var stance = PeddlerCatalog.registry(PeddlerCatalog.load())
                .findLatest("meridian_peddler:horse_stance").orElseThrow();
        context.assertTrue(stance.getCost().getQiCount() == 0, "蹲马步不应扣真元");
        context.assertTrue(stance.getCost().getWork(0).getTraining(), "蹲马步没有训练标记");
        context.assertTrue(stance.getMovementSpeedMultiplier() == 0, "蹲马步没有站定");
        context.assertTrue(stance.getAiHints().getCategoryWeight().getValue() == 0, "蹲马步 AI 权重不是零");
        context.succeed();
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 与正式功法服务使用同一宿主发现入口，只认领本测试登记的身体。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        MemoryTechniqueHost host = HOSTS.get(UUID.fromString(request.getEntityUuid()));
        if (host != null) {
            return host.exchange(payload);
        }
        var response = TechniqueHostResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setUnbound(true /* unbound */)
                .build();
        return TechniqueProtocol.encodeHostResponse(response);
    }

    private static MemoryTechniqueHost registerHost(UUID entityId) {
        // 每个场景只登记自己的身体，避免 GameTest 并行时互相消费活动。
        var host = new MemoryTechniqueHost(entityId, newBody(entityId));
        HOSTS.put(entityId, host);
        return host;
    }

    static void teach(UUID entityId, List<String> volumes) {
        // 掉落用例借同一内存宿主给一只原版生物一具身体，再按顺序学会这几卷；空表就是有身体、一卷不会。
        registerHost(entityId);
        for (String volume : volumes) {
            TechniqueFabric.service().learn(entityId, volume);
        }
    }

    static void forget(UUID entityId) {
        // 场景结束时收回身体，别的场景读不到这只生物。
        HOSTS.remove(entityId);
    }

    private static void rejectVolume(TechniqueService service, UUID entityId, String volumeId) {
        // 缺少前置卷时必须在进度提交前拒绝。
        try {
            service.learn(entityId, volumeId);
            throw new AssertionError("缺少前置卷却接受了：" + volumeId);
        } catch (TechniqueService.PrerequisiteVolumeException expected) {
            // 只接受明确的前置卷拒绝，其他失败应使测试失败。
        }
    }

    private static BodySnapshot newBody(UUID entityId) {
        // 初始快照使用 Human 已登记模板与正式出生构造器。
        var addons = PeddlerCatalog.addons();
        var template = addons.findTemplate(HumanContent.TEMPLATE_ID, HumanContent.CONTENT_VERSION)
                .orElseThrow();
        var time = TimeCursor.newBuilder()
                .setDomain("meridian_peddler:test")
                .setEpoch("gametest")
                .setElapsedMillis(0)
                .build();
        try {
            return BodySnapshot.parseFrom(HumanBirth.candidate(template, entityId.toString(), time));
        } catch (IOException exception) {
            throw new IllegalStateException("无法构造测试人体", exception);
        }
    }

    private static HeldItems emptyHands() {
        // 宿主检视必须带双手结构；目录条件不读取手持物品。
        return HeldItems.newBuilder()
                .setMainHand(HeldItem.getDefaultInstance())
                .setOffHand(HeldItem.getDefaultInstance())
                .setMainHandSide(HandSide.RIGHT)
                .build();
    }

    private static TechniqueHostResponse inspectResponse(String requestId, BodySnapshot body) {
        // 每次读取返回相同 revision 的权威快照与双手事实。
        TechniqueHostFacts facts = TechniqueHostFacts.newBuilder()
                .setGrounded(true)
                .setInWater(false)
                .setClimbing(false)
                .setRiding(false)
                .setGliding(false)
                .build();
        return TechniqueHostResponse.newBuilder()
                .setRequestId(requestId)
                .setHeldItems(emptyHands())
                .setBody(body)
                .setInspectFacts(facts)
                .build();
    }

    private static final class MemoryTechniqueHost implements TechniqueHostEntrypoint {
        private final UUID entityId;
        private BodySnapshot body;

        private MemoryTechniqueHost(UUID entityId, BodySnapshot body) {
            // 测试宿主只认领一具人体，并在活动提交时原子替换其快照进度。
            this.entityId = entityId;
            this.body = body;
        }

        @Override
        public byte[] exchange(byte[] payload) {
            // 请求与回执都通过功法库的字节契约校验。
            TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
            if (!request.getEntityUuid().equals(entityId.toString())) {
                return unbound(request.getRequestId());
            }
            if (request.hasInspect()) {
                return TechniqueProtocol.encodeHostResponse(inspectResponse(request.getRequestId(), body));
            }
            return commit(request);
        }

        private byte[] unbound(String requestId) {
            // 非本宿主的请求不能读取或写入身体。
            var response = TechniqueHostResponse.newBuilder()
                    .setRequestId(requestId)
                    .setUnbound(true /* unbound */)
                    .build();
            return TechniqueProtocol.encodeHostResponse(response);
        }

        private byte[] commit(TechniqueHostRequest request) {
            // 学习活动只更新 Technique 附属进度并递增身体 revision。
            long currentRevision = body.getRevision();
            String requestId = request.getRequestId();
            if (request.getExpectedRevision() != currentRevision) {
                RequestFailure failure = RequestFailure.newBuilder()
                        .setCode(FailureCode.REVISION_CONFLICT)
                        .build();
                var response = TechniqueHostResponse.newBuilder()
                        .setRequestId(requestId)
                        .setFailure(failure)
                        .build();
                return TechniqueProtocol.encodeHostResponse(response);
            }
            BodyActivity activity = request.getActivity();
            long committedRevision = currentRevision + 1;
            body = body.toBuilder()
                    .putAllAddonProgress(activity.getProgressMap())
                    .setRevision(committedRevision)
                    .build();
            TechniqueActivityCommit commit = TechniqueActivityCommit.newBuilder()
                    .setRevision(committedRevision)
                    .setActivity(activity)
                    .build();
            var response = TechniqueHostResponse.newBuilder()
                    .setRequestId(requestId)
                    .setActivity(commit)
                    .build();
            return TechniqueProtocol.encodeHostResponse(response);
        }

        private TechniqueProgress progress() {
            // 从提交后的身体快照读取实际功法进度。
            return TechniqueState.read(BodyProtobuf.fromMessage(body));
        }
    }
}
