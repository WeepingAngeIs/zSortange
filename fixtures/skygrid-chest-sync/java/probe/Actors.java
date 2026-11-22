package probe;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class Actors {
    static final BlockPos HUB=new BlockPos(2,81,1), DOOR=new BlockPos(3,81,1);
    static UUID uuid(String name){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));}
    static ServerPlayer join(MinecraftServer server,String name){
        var profile=new GameProfile(uuid(name),name);
        var p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet,PacketSendListener listener,boolean flush){}
        };
        new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,CommonListenerCookie.createInitial(profile,false));
        survival(p);return p;
    }
    static void survival(ServerPlayer p){
        ChestScenario.require(!p.hasPermissions(2) && p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"Expected nonoperator Survival "+p.getName().getString());
    }
    static void useTop(ServerPlayer p,BlockPos target){
        p.connection.teleport(target.getX()+.5,target.getY()+2.2,target.getZ()+.5,0,90);
        p.setYRot(0);p.setXRot(90);p.setYHeadRot(0);
        BlockHitResult hit=RoomEditor.looking(p);
        ChestScenario.require(hit.getBlockPos().equals(target) && hit.getDirection()==Direction.UP,
                "Fixture ray did not reach "+target+", hit "+hit.getBlockPos());
        p.gameMode.useItemOn(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
    }
    static void held(ServerPlayer p,ItemStack stack){p.setItemInHand(InteractionHand.MAIN_HAND,stack);}
    static void standardTablet(ServerPlayer p){
        p.getInventory().selected=8;
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(8));p.inventoryMenu.broadcastChanges();
        ChestScenario.require(p.getMainHandItem().is(Content.CUPID_TABLET.get()),"Missing ordinary saved tablet");
    }
    static void setup(MinecraftServer server,ServerPlayer p,BlockPos hub,BlockPos door){
        var home=server.overworld();
        for(int x=hub.getX()-3;x<=door.getX()+5;x++)for(int z=hub.getZ()-3;z<=hub.getZ()+5;z++)
            home.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
        p.teleportTo(home,hub.getX()+.5,82,hub.getZ()+2.5,0,0);
        held(p,new ItemStack(Content.HUB.get()));useTop(p,hub.below());
        held(p,new ItemStack(PocketContent.ENTRANCE.get()));useTop(p,door.below());
        p.getInventory().selected=8;held(p,new ItemStack(Content.CUPID_TABLET.get()));
        p.setShiftKeyDown(true);useTop(p,hub);useTop(p,door);p.setShiftKeyDown(false);
        ChestScenario.require(BagInventory.data(p.getMainHandItem()).hasUUID("PocketId"),"Normal hub/cube linking");
    }
    static void enter(ServerPlayer p,BlockPos door){
        p.getInventory().selected=0;held(p,ItemStack.EMPTY);useTop(p,door);
    }
    static void place(ServerPlayer p,BlockPos pos,ItemStack stack,boolean expected){
        p.getInventory().selected=0;held(p,stack);int before=stack.getCount();
        var old=p.serverLevel().getBlockState(pos);useTop(p,pos.below());
        boolean changed=!old.equals(p.serverLevel().getBlockState(pos));
        int after=p.getMainHandItem().getCount();
        ChestScenario.require(changed==expected && after==before-(expected?1:0),
                "Placement/consumption at "+pos+" for "+p.getName().getString()+" expected "+expected+" got "+p.serverLevel().getBlockState(pos)+" count "+after);
    }
    static boolean inside(ServerPlayer p){return p!=null && PocketService.inPocket(p.level());}
}
