package dev.kizuna.meridian.peddler;

import com.mojang.serialization.Codec;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.Stall;
import dev.kizuna.meridian.currency.TradeCounter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

/** 主世界一趟一担；实体 UUID 同时标识这次到访与 Currency 实物钱匣。 */
public final class PeddlerVisits implements ModInitializer {
    static final int CHECK_TICKS = 100;
    static final int INTERVAL = 48000;
    static final int STAY = 12000;
    private static final int ATTEMPTS = 12;
    private static final int MIN_DISTANCE = 8;
    private static final int MAX_DISTANCE = 20;
    private static final int FREE_SLOTS = 4;
    static final Identifier PACK = Identifier.parse("meridian_peddler:pack");
    static final GameRule<Boolean> NATURAL_VISITS = GameRuleBuilder.forBoolean(true /* defaultValue */)
            .category(GameRuleCategory.SPAWNING)
            .buildAndRegister(Identifier.parse("meridian_peddler:natural_visits"));
    private static final ResourceKey<LootTable> LOAD = ResourceKey.create(Registries.LOOT_TABLE, PACK);
    static final AttachmentType<Long> NEXT = clock("next_visit");
    static final AttachmentType<Long> LEAVES = clock("leaves_at");

    private static AttachmentType<Long> clock(String path) {
        // 世界与到访者各自保存游戏刻，睡觉跳过白昼不会提前刷新。
        return AttachmentRegistry.create(Identifier.fromNamespaceAndPath("meridian_peddler", path),
                builder -> builder.persistent(Codec.LONG));
    }

    @Override
    public void onInitialize() {
        // 世界可选择暂停到访的数据包，手动生成和已经开张的货担仍可交互。
        var mod = FabricLoader.getInstance().getModContainer(PACK.getNamespace()).orElseThrow();
        var pack = Identifier.parse("meridian_peddler:no_natural_visits");
        ResourceLoader.registerBuiltinPack(pack, mod, Component.translatable("pack.meridian_peddler.no_natural_visits"),
                PackActivationType.NORMAL);
        // 区块重载后按绝对离场时刻恢复原版倒计时。
        ServerTickEvents.END_LEVEL_TICK.register(PeddlerVisits::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof WanderingTrader trader && trader.hasAttached(LEAVES)) {
                // 无剧情接手的潜行交互也不能向这担货索取原版绿宝石报价。
                trader.getOffers().clear();
                long remaining = trader.getAttachedOrThrow(LEAVES) - level.getGameTime();
                trader.setDespawnDelay((int) Math.clamp(remaining, 1L, STAY));
            }
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer buyer) || hand != InteractionHand.MAIN_HAND
                    || buyer.isShiftKeyDown() || buyer.isSpectator() || !(entity instanceof WanderingTrader trader)
                    || !trader.hasAttached(LEAVES)) {
                return InteractionResult.PASS;
            }
            if (present(trader) && buyer.isWithinEntityInteractionRange(trader, 0)) {
                var stall = new Stall(trader.getUUID(), PACK, trader, (viewer, actor) -> present(trader));
                var greeting = Component.translatable("merchant.meridian_peddler.greeting");
                TradeCounter.open(buyer, stall, List.of(), greeting);
            }
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private static boolean present(WanderingTrader trader) {
        // 开窗和确认时都核对期限，离场当刻收摊。
        return trader.level().getGameTime() < trader.getAttachedOrThrow(LEAVES);
    }

    private static void tick(ServerLevel level) {
        // 仅在有地面落脚且允许自然刷商人的主世界开张。
        long now = level.getGameTime();
        if (level.dimension() != Level.OVERWORLD || now % CHECK_TICKS != 0 || now < level.getAttachedOrElse(NEXT, 0L)) {
            return;
        }
        var rules = level.getGameRules();
        if (!rules.get(NATURAL_VISITS) || !rules.get(GameRules.SPAWN_MOBS)
                || !rules.get(GameRules.SPAWN_WANDERING_TRADERS)) {
            return;
        }
        var players = level.players().stream().filter(player -> player.isAlive() && !player.isSpectator()).toList();
        if (players.isEmpty()) {
            return;
        }
        var random = level.getRandom();
        var near = players.get(random.nextInt(players.size())).blockPosition();
        for (int i = 0; i < ATTEMPTS; i++) {
            int dx = random.nextIntBetweenInclusive(-MAX_DISTANCE, MAX_DISTANCE);
            int dz = random.nextIntBetweenInclusive(-MAX_DISTANCE, MAX_DISTANCE);
            var column = near.offset(dx, 0, dz);
            if (dx * dx + dz * dz < MIN_DISTANCE * MIN_DISTANCE || !level.isPositionEntityTicking(column)) {
                continue;
            }
            var pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            var floor = pos.below();
            if (level.getWorldBorder().isWithinBounds(pos) && level.getFluidState(pos).isEmpty()
                    && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                    && arrive(level, pos)) {
                level.setAttached(NEXT, now + INTERVAL);
                return;
            }
        }
    }

    static boolean arrive(ServerLevel level, BlockPos pos) {
        // 只给成功落脚的新演员装担；重开窗口只读这个 UUID 的钱匣。
        var trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.EVENT);
        if (trader == null) {
            return false;
        }
        trader.snapTo(pos, 0, 0);
        if (!level.noCollision(trader)) {
            return false;
        }
        trader.setAttached(LEAVES, level.getGameTime() + STAY);
        trader.setCustomName(Component.translatable("merchant.meridian_peddler.name"));
        trader.setCustomNameVisible(true /* visible */);
        trader.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BUNDLE));
        trader.setDropChance(EquipmentSlot.MAINHAND, 0);
        trader.setHomeTo(pos, MIN_DISTANCE);
        if (!level.addFreshEntity(trader)) {
            return false;
        }
        var table = level.getServer().reloadableRegistries().getLootTable(LOAD);
        var params = new LootParams.Builder(level).create(LootContextParamSets.EMPTY);
        var stacks = new ArrayList<>(table.getRandomItems(params, level.getRandom()));
        stacks.addAll(Collections.nCopies(FREE_SLOTS, ItemStack.EMPTY));
        CurrencyService.get(level.getServer()).openTill(trader.getUUID(), stacks);
        return true;
    }
}
