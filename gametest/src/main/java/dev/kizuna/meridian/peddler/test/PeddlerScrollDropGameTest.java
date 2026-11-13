package dev.kizuna.meridian.peddler.test;

import dev.kizuna.meridian.technique.fabric.TechniqueScrollItem;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 千里腿的生存来源：玩家亲手杀死会某一卷的流浪商人，才按目录概率掉这一卷。
 *
 * <p>独立装配里没有 Threatening，流浪商人会哪几卷由内存宿主直接教；掉落走原版流浪商人掉落表。</p>
 */
public final class PeddlerScrollDropGameTest {
    private static final String ROAD = "meridian_peddler:road";
    private static final List<String> VOLUMES = List.of(ROAD, "meridian_peddler:ambush", "meridian_peddler:journey");
    private static final int LOOT_ROLLS = 200;
    private static final int LEAST_DROPS = 25;
    private static final int MOST_DROPS = 75;
    private static final int LAST_HURT_TICKS = 100;

    @GameTest(maxTicks = PeddlerGameTestTicks.DEFAULT_TIMEOUT)
    public void wanderingTradersDropOnlyTheVolumesTheyKnow(GameTestHelper context) {
        // 学全三卷的商人三卷各掉约四分之一；只会上卷的只掉上卷；有身体但一卷不会的、不是玩家打死的都不掉。
        ServerLevel level = context.getLevel();
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        Mob master = spawn(context, new BlockPos(1, 2, 1));
        Mob novice = spawn(context, new BlockPos(3, 2, 1));
        Mob layman = spawn(context, new BlockPos(5, 2, 1));
        List<UUID> bodies = List.of(master.getUUID(), novice.getUUID(), layman.getUUID());
        try {
            PeddlerGameTest.teach(master.getUUID(), VOLUMES);
            PeddlerGameTest.teach(novice.getUUID(), List.of(ROAD));
            PeddlerGameTest.teach(layman.getUUID(), List.of());
            DamageSource byPlayer = level.damageSources().playerAttack(player);
            Map<String, Integer> masterDrops = roll(level, master, byPlayer, player);
            Map<String, Integer> noviceDrops = roll(level, novice, byPlayer, player);
            System.out.println("[千里腿掉落] 玩家击杀 " + LOOT_ROLLS + " 次：三卷 " + masterDrops + "，上卷 " + noviceDrops);
            for (String volume : VOLUMES) {
                int drops = masterDrops.get(volume);
                context.assertTrue(within(drops), "学全三卷的商人掉 " + volume + " 偏离 25%：" + drops);
            }
            context.assertTrue(within(noviceDrops.get(ROAD)), "只会上卷的商人掉上卷偏离 25%：" + noviceDrops);
            context.assertTrue(total(noviceDrops) == noviceDrops.get(ROAD), "只会上卷的商人掉了别的卷：" + noviceDrops);
            context.assertTrue(total(roll(level, layman, byPlayer, player)) == 0, "一卷不会的商人也掉卷");
            DamageSource byMob = level.damageSources().mobAttack(layman);
            context.assertTrue(total(roll(level, master, byMob, null)) == 0, "不是玩家打死的商人也掉卷");
        } finally {
            bodies.forEach(PeddlerGameTest::forget);
            level.getServer().getPlayerList().remove(player);
        }
        context.succeed();
    }

    private static Mob spawn(GameTestHelper context, BlockPos relative) {
        // 原版流浪商人，关掉 AI 免得它走开或喝隐身药水。
        Mob trader = context.spawn(EntityTypes.WANDERING_TRADER, relative);
        trader.setNoAi(true);
        return trader;
    }

    private static Map<String, Integer> roll(ServerLevel level, Mob mob, DamageSource source, ServerPlayer killer) {
        // 用原版掉落入口抽 LOOT_ROLLS 次，按卷数掉出来的卷轴；killer 为空算非玩家击杀。
        boolean playerKilled = killer != null;
        if (playerKilled) {
            mob.setLastHurtByPlayer(killer, LAST_HURT_TICKS);
        }
        ResourceKey<LootTable> table = EntityTypes.WANDERING_TRADER.getDefaultLootTable().orElseThrow();
        List<ItemStack> drops = new ArrayList<>();
        for (int roll = 0; roll < LOOT_ROLLS; roll++) {
            mob.dropFromLootTable(level, source, playerKilled, table, drops::add);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String volume : VOLUMES) {
            TechniqueScrollItem scroll = scrollItem(volume);
            counts.put(volume, drops.stream().filter(stack -> stack.is(scroll)).mapToInt(ItemStack::getCount).sum());
        }
        return counts;
    }

    private static TechniqueScrollItem scrollItem(String volume) {
        // 功法库按卷生成的卷轴物品。
        TechniqueScrollItem item = TechniqueScrollItems.scrolls().get(Identifier.parse(volume + "_scroll"));
        if (item == null) {
            throw new IllegalStateException("没有卷轴物品：" + volume);
        }
        return item;
    }

    private static boolean within(int drops) {
        // 200 次 25%：均值 50、标准差约 6.1，取正负四个标准差。
        return drops >= LEAST_DROPS && drops <= MOST_DROPS;
    }

    private static int total(Map<String, Integer> counts) {
        // 三卷掉落合计。
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
