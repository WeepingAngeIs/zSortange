package dev.unyielding.bundlesmoke;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.anya.storage.BackpackAccess;
import dev.anya.storage.BackpackHistory;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Content;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import static dev.unyielding.bundlesmoke.PlayerOnlySmoke.require;

/** A second connected server-side player with an in-memory transport, on the occupied published pack. */
final class IndependentLoss {
    static boolean complete;
    static final Path result = Path.of("independent-loss.txt");

    static void run(ServerPlayer visitor) throws Exception {
        var server=visitor.server;
        var level=server.overworld();
        var profile=new GameProfile(UUID.fromString("16347c63-c7bb-4c31-bdbe-81c6d706d578"),"OtherBagOwner");
        var messages=new ArrayList<String>();
        var connection=new Connection(PacketFlow.SERVERBOUND) {
            @Override public boolean isMemoryConnection() { return true; }
            @Override public void send(Packet<?> packet,PacketSendListener listener,boolean flush) {
                if(packet instanceof ClientboundSystemChatPacket chat) messages.add(chat.content().getString());
            }
        };
        var second=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,second,CommonListenerCookie.createInitial(profile,false));
        second.setGameMode(GameType.SURVIVAL);
        require(!second.hasPermissions(2),"Second player unexpectedly has operator permission");
        require(dev.unyielding.jail.JailIntegrated.activeTransaction(server,second.getUUID())==null,"Other owner is in custody");
        var inv=second.getInventory();
        int slot=inv.getFreeSlot();
        require(slot>=0 && slot<9,"Synthetic second owner needs one free hotbar slot");
        inv.selected=slot;
        var stack=new ItemStack(Content.BACKPACK.get());
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("Second owner's lost bag"));
        inv.setItem(slot,stack);
        var bag=new BagInventory(second,slot);
        var emeralds=new ItemStack(Items.EMERALD,11);
        emeralds.set(DataComponents.CUSTOM_NAME,Component.literal("Other owner's eleven"));
        bag.setItem(3,emeralds);
        var exact=inv.getItem(slot).copy();
        UUID id=BagInventory.data(exact).getUUID("BagId");
        require(second.drop(true),"Native toss failed");
        List<ItemEntity> matches=new ArrayList<>();
        for (var e:level.getAllEntities()) if(e instanceof ItemEntity item && sameId(item.getItem(),id)) matches.add(item);
        require(matches.size()==1,"Expected one loose second bag");
        var drop=matches.getFirst();
        drop.setNeverPickUp();
        require(drop.hurt(drop.damageSources().cactus(),100) && drop.isRemoved(),"Witnessed loss failed");
        require(BackpackHistory.page(second,0).isEmpty(),"History disclosed to an ordinary player");
        server.getPlayerList().op(visitor.getGameProfile());
        String restore;
        try {
            var row=BackpackHistory.page(visitor,0).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();
            require(row.restorable() && row.lossId()!=null && row.owner().equals(second.getUUID()),"Recorded loss owner mismatch");
            restore="anya backpack history restore "+id+" "+row.lossId();
            try {
                require(server.getCommands().getDispatcher().execute(restore,second.createCommandSourceStack())==0,"Ordinary player restored a bag");
            } catch(CommandSyntaxException expected) { /* The real command is hidden from non-operators. */ }
            require(count(second,id,exact)==0 && count(visitor,id,exact)==0,"Denied recovery delivered a copy");
            require(server.getCommands().getDispatcher().execute(restore,visitor.createCommandSourceStack())==1,"Operator command did not execute");
        } finally { server.getPlayerList().deop(visitor.getGameProfile()); }
        int heldCount=count(second,id,exact);
        require(heldCount<=1 && count(visitor,id,exact)==0,"Wrong owner or duplicate during custody");
        CombinedStorageProof.held(visitor);
        require(PlayerOnlySmoke.cmd("unjail Visitor")==1,"Normal release of original owner failed");
        CombinedStorageProof.returned(visitor);
        server.getPlayerList().op(second.getGameProfile());
        try {
            messages.clear();
            require(server.getCommands().getDispatcher().execute(restore,second.createCommandSourceStack())==1,"Post-release recovery unavailable");
            require(count(second,id,exact)==1,"Second genuine loss did not return after release: "+messages);
            require(server.getCommands().getDispatcher().execute(restore,second.createCommandSourceStack())==1,"Could not replay old recovery link");
            require(count(second,id,exact)==1 && count(visitor,id,exact)==0,"Replay duplicated or misdirected second bag");
        } finally { server.getPlayerList().deop(second.getGameProfile()); }
        for(var world:server.getAllLevels()) for(var e:world.getAllEntities()) if(e instanceof ItemEntity i)
            require(!sameId(i.getItem(),id),"Duplicate second bag on ground");
        require(server.saveEverything(false,true,true),"Final native save failed");
        Files.writeString(result,"Storage SHA256 "+System.getenv("STORAGE_SHA")+"\nJail SHA256 "+System.getenv("UQ_JAIL_SHA256")
            +"\nSecond recovered while first held: "+(heldCount==1)+"\nSecond recovered after release: true"
            +"\nOriginal was held and returned once; exact second bag and all eleven marked emeralds returned once to its owner; unauthorized and stale links produced no copies.\n");
        complete=true;
    }

    private static boolean sameId(ItemStack item,UUID id) {
        var data=BagInventory.data(item);
        return data.hasUUID("BagId") && id.equals(data.getUUID("BagId"));
    }
    private static int count(ServerPlayer player,UUID id,ItemStack exact) {
        int n=0;
        for(var src:BackpackAccess.all(player)) if(sameId(src.stack(),id)) {
            require(ItemStack.matches(src.stack(),exact),"Bag with matching identity changed");
            n+=src.stack().getCount();
        }
        return n;
    }
}
