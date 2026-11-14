package dev.anya.storage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(AnyaStorage.ID)
public final class AnyaStorage {
    public static final String ID="anyastorage";
    public static final Logger LOG=LogUtils.getLogger();
    public AnyaStorage(IEventBus bus) {
        Config.init(); Content.register(bus); bus.addListener(Packets::register);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::commands);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::breakBlock);
        NeoForge.EVENT_BUS.addListener(Network::tick);
        NeoForge.EVENT_BUS.addListener(Upgrades::tick);
        NeoForge.EVENT_BUS.addListener(Upgrades::dragonDeath);
        NeoForge.EVENT_BUS.addListener(AnyaStorage::stopped);
    }
    private static void stopped(ServerStoppedEvent e) { Network.clear(); Safety.clear(); }
    public static void tell(ServerPlayer player,String message) { player.sendSystemMessage(Component.literal("[Anya Storage] "+message)); }
    private static void commands(RegisterCommandsEvent event) {
        var storage=Commands.literal("storage").executes(c->{StoreMenu.settings(c.getSource().getPlayerOrException());return 1;})
            .then(Commands.literal("status").executes(c->{c.getSource().sendSuccess(()->Component.literal(Config.lastStatus+" | "+Safety.status()),false);return 1;}));
        var root=Commands.literal("anya").then(storage)
            .then(Commands.literal("quickstack").executes(c->{Network.quick(c.getSource().getPlayerOrException(),false);return 1;}))
            .then(Commands.literal("dump").executes(c->{Network.quick(c.getSource().getPlayerOrException(),true);return 1;}))
            .then(Commands.literal("slots").executes(c->{StoreMenu.preferences(c.getSource().getPlayerOrException());return 1;}));
        event.getDispatcher().register(root);
        if(event.getDispatcher().getRoot().getChild("as")==null)
            event.getDispatcher().register(Commands.literal("as").executes(c->{StoreMenu.settings(c.getSource().getPlayerOrException());return 1;}));
    }
    private static void breakBlock(BlockEvent.BreakEvent event) {
        if(event.getPlayer() instanceof ServerPlayer p && event.getLevel().getBlockEntity(event.getPos()) instanceof StorageEntity be && !be.ownedBy(p)) {
            event.setCanceled(true); tell(p,"Only this storage block's owner or an operator can break it.");
        }
    }
}
