package dev.kizuna.meridian.currency.client;

import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.meridian.currency.TradeCounter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/** 生存玩家右键自然到访的货郎，从正式窗口买卷卖皮。 */
public final class PeddlerTradeScene implements FabricClientGameTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int SCALE = 2;
    private static final int SCAN_LIMIT = 100;
    private static final int POLL_TICKS = 5;
    private static final int RADIUS = 48;
    private static final int FACING_NORTH = 180;
    private static final double DISTANCE = 2.5;
    private static final int SILVER = 2;
    private static final int HALF = 2;
    private ClientGameTestContext ctx;
    private TradeModule module;
    private boolean dialogue;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 测试与窗口同包以读取实际绘制的点击区域。
        if (!"trade".equals(System.getProperty("meridian.peddler.scene"))) {
            return;
        }
        ctx = context;
        module = FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui", ClientModule.class).stream()
                .filter(TradeModule.class::isInstance).map(TradeModule.class::cast).findFirst().orElseThrow();
        try (var world = ctx.worldBuilder().create()) {
            var server = world.getServer();
            prepare(server);
            var actor = awaitArrival(server);
            server.runOnServer(s -> approach(s, actor));
            world.getConnection().waitForChunksRender();
            ctx.waitFor(client -> client.level.getEntity(actor) != null);
            ctx.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES,
                    client.level.getEntity(actor).getEyePosition()));
            ctx.waitFor(client -> client.hitResult instanceof EntityHitResult hit
                    && hit.getEntity().getUUID().equals(actor));
            shot("arrival");
            checkStory(server, actor);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            ctx.waitFor(client -> module.view != null && module.view.session.bill != null);
            checkBackdrop(shot("open"));
            transact("purchase");
            shot("purchase");
            click("sell");
            ctx.waitFor(client -> module.view.session.bill != null);
            transact("sale");
            shot("sale");
            server.runOnServer(s -> verify(s, actor));
            System.out.println("PEDDLER_CLIENT_OK natural_arrival_right_click_buy_sell");
        }
    }

    private void prepare(TestServerContext server) {
        // 给玩家准备交易物品，时钟照常走，白天便于拍摄。
        ctx.getInput().resizeWindow(WIDTH, HEIGHT);
        server.runCommand("time set noon");
        server.runCommand("gamerule advance_time false");
        server.runCommand("gamerule spawn_mobs true");
        server.runCommand("gamerule spawn_wandering_traders true");
        server.runCommand("weather clear");
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            var inventory = player.getInventory();
            inventory.clearContent();
            var silver = BuiltInRegistries.ITEM.getOptional(Identifier.parse("meridian_currency:silver_fragment"));
            inventory.add(new ItemStack(silver.orElseThrow(), SILVER));
            inventory.add(new ItemStack(Items.LEATHER));
        });
        ctx.runOnClient(client -> {
            client.gui.setScreen(null);
            client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            client.options.guiScale().set(SCALE);
            client.resizeGui();
            var runtime = InventoryUiClient.runtime();
            runtime.motion(false /* enabled */);
            runtime.uiVersion(UiVersion.MODERN);
        });
    }

    private UUID awaitArrival(TestServerContext server) {
        // 真实时钟驱动到访，测试既不生成实体也不调用开窗入口。
        for (int i = 0; i < SCAN_LIMIT; i++) {
            var found = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayers().getFirst();
                var area = player.getBoundingBox().inflate(RADIUS);
                return s.overworld().getEntitiesOfClass(WanderingTrader.class, area,
                        actor -> actor.hasCustomName() && actor.getMainHandItem().is(Items.BUNDLE))
                        .stream().map(WanderingTrader::getUUID).findFirst();
            });
            if (found.isPresent()) {
                return found.get();
            }
            ctx.waitTicks(POLL_TICKS);
        }
        throw new AssertionError("natural_arrival_right_click_buy_sell: 生存世界未遇到行脚货郎");
    }

    private static void approach(MinecraftServer server, UUID id) {
        // 只固定拍摄位置和朝向；演员、货担和交易全部来自正式到访。
        var level = server.overworld();
        var actor = (WanderingTrader) level.getEntity(id);
        actor.setNoAi(true /* noAi */);
        var pos = actor.position();
        var player = server.getPlayerList().getPlayers().getFirst();
        player.teleportTo(level, pos.x, pos.y, pos.z + DISTANCE, Set.of(), FACING_NORTH, 0, true /* setCamera */);
        actor.setYRot(0);
        actor.setYHeadRot(0);
        actor.yBodyRot = 0;
    }

    private void checkStory(TestServerContext server, UUID actor) {
        // 真实剧情模块给有此出身的玩家发可点击邀请，随后松开潜行再看货。
        var action = Identifier.parse("meridian_story:dialogue");
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message.getSiblings().stream().map(part -> part.getStyle().getClickEvent())
                    .anyMatch(event -> event instanceof net.minecraft.network.chat.ClickEvent.Custom click
                            && action.equals(click.id()))) {
                dialogue = true;
            }
        });
        server.runCommand("meridian debug origin set @p meridian_story:ordinary");
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            player.setShiftKeyDown(true /* sneaking */);
            var callback = net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker();
            callback.interact(player, player.level(), net.minecraft.world.InteractionHand.MAIN_HAND,
                    player.level().getEntity(actor), null);
            player.setShiftKeyDown(false /* sneaking */);
        });
        ctx.waitFor(client -> dialogue && module.view == null);
        System.out.println("PEDDLER_CLIENT_OK sneaking_story_dialogue");
    }

    private void transact(String button) {
        // 等待服务端成交回执后再拍屏或切页。
        click(button);
        ctx.waitFor(client -> module.view.session.notice.equals(TradeCounter.label("done")));
    }

    private void click(String key) {
        // 把鼠标点到正式控件的中心，走屏幕事件与网络报价。
        ctx.waitTick();
        ctx.runOnClient(client -> {
            var box = module.view.target(key).rect();
            var left = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
            var event = new MouseButtonEvent(box.x() + box.width() / HALF, box.y() + box.height() / HALF, left);
            var screen = client.gui.screen();
            screen.mouseClicked(event, false /* doubleClick */);
            screen.mouseReleased(event);
        });
    }

    private Path shot(String name) {
        // 保存原始分辨率的游戏帧。
        ctx.runOnClient(client -> client.gui.toastManager().clear());
        ctx.waitTick();
        return ctx.takeScreenshot(TestScreenshotOptions.of("peddler-" + name).disableCounterPrefix());
    }

    private void checkBackdrop(Path shot) {
        // 同一行两段空白分别挡住货郎和草地，实拍底色应当相同。
        BufferedImage frame;
        try {
            frame = ImageIO.read(shot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var buy = ctx.computeOnClient(client -> module.view.target("purchase").rect());
        var sell = ctx.computeOnClient(client -> module.view.target("sell").rect());
        int line = (buy.y() + buy.height() + sell.height()) * SCALE;
        int merchant = (buy.x() + 1) * SCALE;
        int ground = (sell.x() + 1) * SCALE;
        if (frame.getRGB(merchant, line) != frame.getRGB(ground, line)) {
            throw new AssertionError("opaque_trade_backdrop: 货郎透过货单底色");
        }
        System.out.println("PEDDLER_CLIENT_OK opaque_trade_backdrop");
    }

    private static void verify(MinecraftServer server, UUID id) {
        // 最后的服务端实物同时覆盖玩家拿走的卷、交出的皮与货郎收到的皮。
        var player = server.getPlayerList().getPlayers().getFirst();
        var currency = dev.kizuna.meridian.currency.CurrencyService.get(server);
        var purse = currency.player(player).contents();
        var scroll = BuiltInRegistries.ITEM.getOptional(Identifier.parse("meridian_peddler:road_scroll")).orElseThrow();
        var drawer = currency.openTill(id).contents();
        boolean hasScroll = purse.stream().anyMatch(stack -> stack.is(scroll));
        if (!hasScroll || purse.stream().anyMatch(stack -> stack.is(Items.LEATHER))
                || drawer.stream().noneMatch(stack -> stack.is(Items.LEATHER))) {
            throw new AssertionError("natural_arrival_right_click_buy_sell: 玩家或货郎实物不符");
        }
    }
}
