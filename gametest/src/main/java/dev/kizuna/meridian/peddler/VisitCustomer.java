package dev.kizuna.meridian.peddler;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.currency.CurrencyService;
import dev.kizuna.meridian.currency.MerchantOffers.Page;
import dev.kizuna.meridian.currency.MerchantService;
import dev.kizuna.meridian.currency.TradeWire;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

record VisitCustomer(ServerPlayer player, RecordingConnection connection) implements AutoCloseable {
    static VisitCustomer login(ServerLevel level, String name) {
        // 建立真实的玩家列表项，记录普通交互发出的窗口数据。
        var profile = new GameProfile(UUID.randomUUID(), name);
        var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
        var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        var connection = new RecordingConnection(List.of(TradeWire.Open.TYPE.id()));
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        return new VisitCustomer(player, connection);
    }

    InteractionResult use(Entity actor) {
        // 用 Fabric 的主手实体交互入口，原版仅在 PASS 时接手。
        player.setPos(actor.position());
        connection.packets().clear();
        var callback = UseEntityCallback.EVENT.invoker();
        return callback.interact(player, player.level(), InteractionHand.MAIN_HAND, actor, null);
    }

    TradeWire.Open open(Entity actor) {
        // 从本次右键回包取会话，不额外调用服务端开摊。
        use(actor);
        return connection.packets().stream().filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                .filter(TradeWire.Open.class::isInstance).map(TradeWire.Open.class::cast).findFirst().orElseThrow();
    }

    void settle(TradeWire.Open open, Page page) {
        // 两页的第一行分别是上卷与皮革，确认使用服务端的原报价。
        var quote = select(open, page);
        confirm(quote);
    }

    MerchantService.Quoted select(TradeWire.Open open, Page page) {
        // 从打开窗口时看到的货单版本取价。
        var trades = CurrencyService.get(player.level().getServer()).merchants();
        var pick = new MerchantService.Selection(open.stock().version(), page, 0, 1, 1);
        return trades.select(player, open.session(), pick);
    }

    void confirm(MerchantService.Quoted quote) {
        // 两个顾客各持自己的服务器报价凭据。
        CurrencyService.get(player.level().getServer()).merchants().confirm(player, quote.ticket());
    }

    void carry(ItemStack stack) {
        // 从普通生存背包收付。
        player.getInventory().add(stack);
    }

    @Override
    public void close() {
        // 触发原版断线与会话清理。
        connection.disconnectFromClient();
    }
}
