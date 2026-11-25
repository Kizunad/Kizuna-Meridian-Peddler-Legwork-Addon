package dev.kizuna.meridian.peddler;

import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.MerchantOffers.Page;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

public final class VisitGameTest {
    private static final int ROAD_LOAD = 3;
    private static final int SILVER = 2;
    private static final int FRAGMENT_VALUE = 100;
    private static final int BUY_CHANGE = 80;
    private static final int LEATHER_PRICE = 35;
    private static final int SEARCH_RADIUS = 40;
    private static final BlockPos ASIDE = new BlockPos(3, 1, 1);
    private static final int RESULT_SLOT = 2;
    private static final String ROAD = "meridian_peddler:road_scroll";
    private static final String CASH = "meridian_currency:copper_cash";
    private static final String FRAGMENT = "meridian_currency:silver_fragment";

    private static Item item(String id) {
        // 所有实物都取正式注册表。
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElseThrow();
    }

    private static final int ARRIVAL_TIMEOUT = PeddlerVisits.CHECK_TICKS * 4;

    @GameTest(maxTicks = ARRIVAL_TIMEOUT, environment = "meridian_peddler_test:quiet_road")
    public void defaultWorldSkipsNaturalVisits(GameTestHelper test) {
        // 默认环境跨过多个排期检查点，玩家附近应当没有货郎，世界也不应写下新排期。
        var level = test.getLevel().getServer().overworld();
        var customer = VisitCustomer.login(level, "QuietRoad");
        test.runAfterDelay(ARRIVAL_TIMEOUT - 1, () -> {
            try (customer) {
                var area = customer.player().getBoundingBox().inflate(SEARCH_RADIUS);
                var traders = level.getEntitiesOfClass(WanderingTrader.class, area,
                        actor -> actor.hasAttached(PeddlerVisits.LEAVES));
                test.assertTrue(!level.getGameRules().get(PeddlerVisits.NATURAL_VISITS), "默认到访规则已开启");
                test.assertTrue(!level.hasAttached(PeddlerVisits.NEXT), "默认世界排了自然到访");
                test.assertTrue(traders.isEmpty(), "默认世界出现自然货郎");
            }
            System.out.println("PEDDLER_TEST_OK default_world_skips_natural_visits");
            test.succeed();
        });
    }

    @GameTest(maxTicks = ARRIVAL_TIMEOUT, environment = "meridian_peddler_test:road")
    public void naturalVisitReachesSurvivalPlayer(GameTestHelper test) {
        // 推进真实世界事件，让主世界玩家附近出现有期限、有实物的货郎。
        var level = test.getLevel().getServer().overworld();
        var customer = VisitCustomer.login(level, "RoadVisitor");
        var player = customer.player();
        var previous = level.getAttached(PeddlerVisits.NEXT);
        level.removeAttached(PeddlerVisits.NEXT);
        test.runAfterDelay(ARRIVAL_TIMEOUT - 1, () -> {
            try (customer) {
                var area = player.getBoundingBox().inflate(SEARCH_RADIUS);
                long next = level.getAttachedOrElse(PeddlerVisits.NEXT, 0L);
                long due = next - PeddlerVisits.INTERVAL + PeddlerVisits.STAY;
                var nearby = level.getEntitiesOfClass(WanderingTrader.class, area,
                        actor -> actor.getAttachedOrElse(PeddlerVisits.LEAVES, 0L) == due);
                test.assertTrue(nearby.size() == 1, "自然到访数应为一，得到 " + nearby.size());
                var actor = nearby.getFirst();
                test.assertTrue(next > level.getGameTime(), "未登记下次到访");
                test.assertTrue(customer.open(actor).stock().buy().getFirst().available() == ROAD_LOAD, "担中无卷");
                actor.discard();
            } finally {
                level.setAttached(PeddlerVisits.NEXT, previous);
            }
            System.out.println("PEDDLER_TEST_OK natural_visit_reaches_survival_player");
            test.succeed();
        });
    }

    @GameTest
    public void survivalTradesShareVisit(GameTestHelper test) {
        // 甲买卷卖皮，乙从自己的窗口看同一担的余货。
        var level = test.getLevel();
        var trader = visit(test);
        try (var a = VisitCustomer.login(level, "PackBuyer"); var b = VisitCustomer.login(level, "PackWatcher")) {
            a.carry(new ItemStack(item(FRAGMENT), SILVER));
            a.carry(new ItemStack(Items.LEATHER));
            var before = b.open(trader);
            test.assertTrue(before.stock().buy().getFirst().available() == ROAD_LOAD, "乙看到的初始卷数");
            a.settle(a.open(trader), Page.BUY);
            var service = CurrencyService.get(level.getServer());
            var purse = service.player(a.player()).contents();
            test.assertTrue(count(purse, item(ROAD)) == 1 && count(purse, item(CASH)) == BUY_CHANGE, "甲买卷的实物和找零");
            a.settle(a.open(trader), Page.SELL);
            var after = service.player(a.player()).contents();
            var drawer = service.openTill(trader.getUUID()).contents();
            test.assertTrue(count(after, Items.LEATHER) == 0 && count(drawer, Items.LEATHER) == 1, "皮革交割");
            int remaining = count(after, item(CASH)) + count(after, item(FRAGMENT)) * FRAGMENT_VALUE;
            test.assertTrue(remaining == BUY_CHANGE + LEATHER_PRICE, "卖皮实收");
            test.assertTrue(b.open(trader).stock().buy().getFirst().available() == ROAD_LOAD - 1, "两人未共摊");
        }
        System.out.println("PEDDLER_TEST_OK survival_trades_share_visit");
        test.succeed();
    }

    @GameTest
    public void reloadPreservesPackAndNewVisitLoadsOnce(GameTestHelper test) {
        // 旧演员经实体存档卸载重载，另一趟实体使用自己的新担。
        var level = test.getLevel();
        var first = visit(test);
        try (var owner = VisitCustomer.login(level, "PackReload")) {
            owner.carry(new ItemStack(item(FRAGMENT), SILVER));
            owner.settle(owner.open(first), Page.BUY);
            var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            test.assertTrue(first.save(saved), "实体未保存");
            first.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            var returned = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.LOAD);
            var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved.buildResult());
            returned.load(input);
            test.assertTrue(level.addFreshEntity(returned), "实体未重载");
            test.assertTrue(returned.getUUID().equals(first.getUUID()), "到访身份变化");
            test.assertTrue(owner.open(returned).stock().buy().getFirst().available() == ROAD_LOAD - 1, "重开补满");
            test.assertTrue(returned.getDespawnDelay() > 0, "离场期限未恢复");
            returned.discard();
            owner.player().setPos(Vec3.atBottomCenterOf(test.absolutePos(ASIDE)));
            var next = visit(test);
            test.assertTrue(!next.getUUID().equals(first.getUUID()), "新到访复用旧担");
            var opening = owner.open(next);
            test.assertTrue(opening.stock().buy().getFirst().available() == ROAD_LOAD, "新到访没有装满一担");
            owner.carry(new ItemStack(item(FRAGMENT), SILVER));
            owner.settle(opening, Page.BUY);
            test.assertTrue(owner.open(next).stock().buy().getFirst().available() == ROAD_LOAD - 1, "新到访重复装担");
        }
        System.out.println("PEDDLER_TEST_OK reload_preserves_pack_and_new_visit_loads_once");
        test.succeed();
    }

    @GameTest
    public void ordinaryMerchantsKeepVanillaTrades(GameTestHelper test) {
        // 同名普通流浪商人与村民都走原版菜单；真正在村民输出格取一次货。
        var level = test.getLevel();
        try (var buyer = VisitCustomer.login(level, "EmeraldBuyer")) {
            var wanderer = test.spawn(EntityTypes.WANDERING_TRADER, 1, 1, 1);
            wanderer.setCustomName(net.minecraft.network.chat.Component.translatable("merchant.meridian_peddler.name"));
            test.assertTrue(buyer.use(wanderer) == InteractionResult.PASS, "普通流浪商人的入口被截获");
            wanderer.mobInteract(buyer.player(), InteractionHand.MAIN_HAND);
            test.assertTrue(buyer.player().containerMenu instanceof MerchantMenu, "流浪商人的原版菜单未开");
            buyer.player().closeContainer();
            var villager = test.spawn(EntityTypes.VILLAGER, 1, 1, 1);
            villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(),
                    VillagerProfession.FARMER));
            var offers = new MerchantOffers();
            var offer = new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 1, 1, 0);
            offers.add(offer);
            villager.setOffers(offers);
            test.assertTrue(buyer.use(villager) == InteractionResult.PASS, "普通村民的入口被截获");
            villager.mobInteract(buyer.player(), InteractionHand.MAIN_HAND);
            var menu = (MerchantMenu) buyer.player().containerMenu;
            buyer.carry(new ItemStack(Items.EMERALD));
            menu.tryMoveItems(0);
            menu.quickMoveStack(buyer.player(), RESULT_SLOT);
            var purse = CurrencyService.get(level.getServer()).player(buyer.player()).contents();
            test.assertTrue(offer.getUses() == 1 && count(purse, Items.EMERALD) == 0 && count(purse, Items.BREAD) == 1,
                    "原版绿宝石交易未成交");
            buyer.player().closeContainer();
            villager.discard();
            wanderer.discard();
            buyer.player().setPos(Vec3.atBottomCenterOf(test.absolutePos(ASIDE)));
            var peddler = visit(test);
            buyer.player().setShiftKeyDown(true /* sneaking */);
            test.assertTrue(buyer.use(peddler) == InteractionResult.PASS, "潜行交谈被货郎截获");
            peddler.mobInteract(buyer.player(), InteractionHand.MAIN_HAND);
            test.assertTrue(!(buyer.player().containerMenu instanceof MerchantMenu), "货郎潜行打开了绿宝石菜单");
        }
        System.out.println("PEDDLER_TEST_OK ordinary_merchants_keep_vanilla_trades");
        test.succeed();
    }

    private static WanderingTrader visit(GameTestHelper test) {
        // 在结构的安全格走真实到访装担入口。
        var level = test.getLevel();
        var spot = test.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        test.assertTrue(PeddlerVisits.arrive(level, spot), "到访位置不可用");
        return level.getEntitiesOfClass(WanderingTrader.class, new AABB(spot),
                trader -> trader.hasAttached(PeddlerVisits.LEAVES)).getFirst();
    }

    private static int count(List<ItemStack> stacks, Item item) {
        // 核对结算后实际格子中的总数。
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
