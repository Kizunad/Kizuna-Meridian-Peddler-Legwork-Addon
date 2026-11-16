package dev.kizuna.meridian.peddler.clienttest;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 拳脚不要求着地的连拍：玩家学了袖手拳上卷和千里腿，起跳后、带着缓降从高处落下时各出一拳、一腿；
 * 再在地面和半空各放一次飞踹，核对地面跳得起来、半空不再往上升。
 * 镜头是玩家自己的第三人称正面；一张接一张拍，同时记下每张的玩家 tick、高度、着没着地、这一式有没有起成。
 */
public final class AirborneStrikeClientTest implements FabricClientGameTest {
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final List<String> VOLUMES = List.of("meridian_villager:outer", "meridian_peddler:road",
            "meridian_peddler:ambush", "meridian_peddler:journey");
    private static final String FIST = "meridian_villager:crushing_fist";
    private static final String KICK = "meridian_peddler:snap_kick";
    private static final String FLYING_KICK = "meridian_peddler:flying_kick";
    /** 地面起的飞踹至少要跳这么高；空中起的飞踹任何一张都不许比上一张高出这么多。 */
    private static final double LEAP_HEIGHT = 0.5;
    private static final double RISE_TOLERANCE = 0.01;
    private static final String TRUE_QI = "true_qi";
    private static final int PLAYER_QI = 300;
    private static final int QI_DENSITY = 1;
    private static final int CLEAR_RADIUS = 16;
    private static final int CLEAR_HEIGHT = 24;
    private static final int SETTLE_TICKS = 10;
    private static final Vec3 PLAYER_FEET = new Vec3(0.5, 0, 0.5);
    private static final Vec3 SOUTH = new Vec3(0.5, 1.62, 8.5);
    private static final double EYE_HEIGHT = 1.62;
    /** 原版起跳的竖直初速度。 */
    private static final double JUMP_SPEED = 0.42;
    /** 离地后第几张出手：这时已经离地。 */
    private static final int JUMP_CAST_TICK = 2;
    /** 带着缓降从这么高放下来：落到地要两秒多，起手一秒上下的式出手那一刻还在半空。 */
    private static final double DROP_HEIGHT = 12;
    private static final int DROP_CAST_TICK = 2;
    private static final int CLIP_TICKS = 70;
    private static final int BETWEEN_CLIPS_TICKS = 120;
    private static final boolean CLEAR_SENT_HISTORY = false;
    private static final String RECORD = "airborne.tsv";

    /** 一段：名字、放哪一式、怎么离地。 */
    private record Clip(String name, String technique, Launch launch) { }

    /** 怎么离地。 */
    private enum Launch { GROUND, JUMP, DROP }

    private static final List<Clip> CLIPS = List.of(new Clip("jump_fist", FIST, Launch.JUMP),
            new Clip("drop_fist", FIST, Launch.DROP), new Clip("jump_kick", KICK, Launch.JUMP),
            new Clip("drop_kick", KICK, Launch.DROP), new Clip("ground_flying_kick", FLYING_KICK, Launch.GROUND),
            new Clip("drop_flying_kick", FLYING_KICK, Launch.DROP));

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            Vec3 base = Vec3.atLowerCornerOf(prepare(context, world));
            arm(context, world, base);
            List<String> rows = new ArrayList<>();
            for (Clip clip : CLIPS) {
                film(context, world, base, clip, rows);
            }
            write(RECORD, "段\t张\t玩家 tick\t离地高度\t着地\t这一式\n" + String.join("\n", rows) + "\n");
        }
    }

    private static BlockPos prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        // 原点取 (0, 0) 处的地表；正午、晴天，界面藏起来，四周清成平地。
        int surface = world.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
        BlockPos origin = new BlockPos(0, surface, 0);
        command(world, "time set noon");
        command(world, "gamerule advance_time false");
        command(world, "weather clear");
        context.runOnClient(client -> {
            // 新玩家进世界会弹出身抽选界面，整屏挡住画面；合上它等于点「先不选」。
            client.gui.setScreen(null);
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
        BlockPos from = origin.offset(-CLEAR_RADIUS, 0, -CLEAR_RADIUS);
        BlockPos to = origin.offset(CLEAR_RADIUS, CLEAR_HEIGHT, CLEAR_RADIUS);
        command(world, "fill " + coordinates(from) + " " + coordinates(to) + " air");
        command(world, "fill " + coordinates(from.below()) + " " + coordinates(to.atY(origin.getY() - 1))
                + " grass_block");
        AABB area = new AABB(Vec3.atLowerCornerOf(from), Vec3.atLowerCornerOf(to.offset(1, 1, 1)));
        world.getServer().runOnServer(server -> server.overworld()
                .getEntities((Entity) null, area, entity -> !(entity instanceof Player))
                .forEach(Entity::discard));
        return origin;
    }

    private static void arm(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
        // 生存模式、学袖手拳上卷和千里腿三卷、开脉、升真炁；空着手，站在原点朝南，镜头转到身前。
        command(world, "gamemode survival @a");
        VOLUMES.forEach(volume -> command(world, "meridian technique learn @a " + volume));
        command(world, "meridian opening complete @a all");
        command(world, "meridian body realm @a " + TRUE_QI);
        stand(context, world, base);
        context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        settle(context, world);
    }

    private static void stand(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
        // 回到原点朝南站好：偏航 0 朝 +Z，俯仰为正时低头；tp 给的是脚底坐标，眼睛在其上方 1.62 格。
        Vec3 feet = base.add(PLAYER_FEET);
        Vec3 direction = base.add(SOUTH).subtract(feet.add(0, EYE_HEIGHT, 0));
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(Math.atan2(-direction.y, direction.horizontalDistance()));
        command(world,
                String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", feet.x, feet.y, feet.z, yaw, pitch));
        settle(context, world);
    }

    private static void film(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base, Clip clip,
                             List<String> rows) {
        // 把身体养好、真元补满，离地，第二张出手，然后一张接一张拍。
        // 每张记：玩家 tick、离地高度、着没着地、这一式起成没有。
        command(world, "effect clear @a");
        command(world, "meridian body heal @a all");
        command(world, "meridian qi set @a " + PLAYER_QI + " all lumen " + QI_DENSITY);
        stand(context, world, base);
        int castTick = launch(world, clip.launch());
        String[] cast = {"还没出手"};
        List<Double> heights = new ArrayList<>();
        for (int frame = 1; frame <= CLIP_TICKS; frame++) {
            if (frame == castTick) {
                cast[0] = world.getServer().computeOnServer(server -> cast(player(server), clip.technique()));
            }
            context.waitTicks(1);
            String state = shoot(context, String.format(Locale.ROOT, "%s_%03d", clip.name(), frame), base.y);
            rows.add(clip.name() + "\t" + frame + "\t" + state + "\t" + cast[0]);
            heights.add(Double.parseDouble(state.split("\t")[1]));
        }
        if (clip.technique().equals(FLYING_KICK)) {
            checkLeap(clip, heights, cast[0]);
        }
        context.waitTicks(BETWEEN_CLIPS_TICKS);
    }

    private static void checkLeap(Clip clip, List<Double> heights, String cast) {
        // 飞踹的向上冲量是蹬地起跳：地面起手要跳得起来；空中起手从头到尾只许往下落，不许再升。
        if (!cast.contains("阶段=WARMUP")) {
            throw new AssertionError(clip.name() + " 飞踹没起成：" + cast);
        }
        if (clip.launch() == Launch.GROUND) {
            double peak = heights.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            if (peak < LEAP_HEIGHT) {
                throw new AssertionError("地面起手的飞踹没跳起来，最高只到 " + peak);
            }
            return;
        }
        for (int index = 1; index < heights.size(); index++) {
            if (heights.get(index) > heights.get(index - 1) + RISE_TOLERANCE) {
                throw new AssertionError("空中起手的飞踹又往上升了：第 " + (index + 1) + " 张 "
                        + heights.get(index - 1) + " → " + heights.get(index));
            }
        }
    }

    private static int launch(TestSingleplayerContext world, Launch launch) {
        // 站地：原地出手。起跳：给一个原版起跳的竖直初速度。落下：带上缓降效果放到 12 格高处。返回第几张出手。
        if (launch == Launch.GROUND) {
            return JUMP_CAST_TICK;
        }
        if (launch == Launch.JUMP) {
            world.getServer().runOnServer(server -> {
                ServerPlayer player = player(server);
                player.setDeltaMovement(0, JUMP_SPEED, 0);
                player.hurtMarked = true;
            });
            return JUMP_CAST_TICK;
        }
        command(world, "effect give @a minecraft:slow_falling 30 0 true");
        command(world, String.format(Locale.ROOT, "tp @a ~ ~%.1f ~", DROP_HEIGHT));
        return DROP_CAST_TICK;
    }

    private static String cast(ServerPlayer caster, String technique) {
        // 精确引用取生产注册表，身体从原版伤害桥读。回报起手那一刻着没着地、起成没有、没起成的原因。
        var definition = TechniqueFabric.service().registry().findLatest(technique).orElseThrow();
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        var request = CastRequest.newBuilder()
                .setCastId("airborne_" + UUID.randomUUID())
                .setBodyId(VanillaDamageFabric.inspectBody(caster).orElseThrow().getId())
                .setTechnique(reference)
                .setEntry(CastEntry.AUTO)
                .build();
        CastOutcome outcome = TechniqueFabric.service().cast(caster.getUUID(), request);
        return "起手时着地=" + caster.onGround() + " 阶段=" + outcome.getPhase() + " 失败=" + outcome.getFailure()
                + " " + outcome.getReason();
    }

    private static ServerPlayer player(MinecraftServer server) {
        // 单人世界里只有测试客户端这一个玩家。
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void settle(ClientGameTestContext context, TestSingleplayerContext world) {
        // 等服务端结果同步到客户端，再等区块网格重建。
        context.waitTicks(SETTLE_TICKS);
        world.getConnection().waitForChunksRender();
    }

    private static String shoot(ClientGameTestContext context, String name, double ground) {
        // 命令回显和右上角提示会挡画面，拍之前清掉。顺手在客户端读这一张的状态：玩家活了多少 tick、离地多高、着没着地。
        // 一张不一定正好隔一个 tick（截图和来回切线程都要花 tick），所以把 tick 数记下来，合成录像时按它定快慢。
        String state = context.computeOnClient(client -> {
            client.gui.hud.getChat().clearMessages(CLEAR_SENT_HISTORY);
            client.gui.toastManager().clear();
            return String.format(Locale.ROOT, "%d\t%.2f\t%s", client.player.tickCount,
                    client.player.getY() - ground, client.player.onGround());
        });
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
        return state;
    }

    private static void command(TestSingleplayerContext world, String command) {
        // 以服务端身份执行，和管理员在游戏里输入的一样。
        world.getServer().runCommand(command);
    }

    private static void write(String name, String content) {
        // 写到本次运行目录里。
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve(name), content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
