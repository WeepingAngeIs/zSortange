package probe;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.*;
import java.nio.file.*;
import java.util.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import com.mojang.authlib.GameProfile;

@Mod("buildprobe")
@EventBusSubscriber(modid="buildprobe")
public final class RoomServer {
    static final net.neoforged.neoforge.registries.DeferredRegister.Blocks MOD_BLOCKS=net.neoforged.neoforge.registries.DeferredRegister.createBlocks("buildprobe");
    static final net.neoforged.neoforge.registries.DeferredRegister.Items MOD_ITEMS=net.neoforged.neoforge.registries.DeferredRegister.createItems("buildprobe");
    static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> MOD_BLOCK=MOD_BLOCKS.register("panel",()->new net.minecraft.world.level.block.Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()));
    static final java.util.function.Supplier<net.minecraft.world.item.BlockItem> MOD_ITEM=MOD_ITEMS.registerSimpleBlockItem(MOD_BLOCK);
    public RoomServer(net.neoforged.bus.api.IEventBus bus){MOD_BLOCKS.register(bus);MOD_ITEMS.register(bus);}

    static final BlockPos HUB=new BlockPos(2,81,1), DOOR=new BlockPos(3,81,1);
    private static ServerPlayer join(net.minecraft.server.MinecraftServer server,String name,UUID uuid)throws Exception{
        var level=server.overworld();var profile=new GameProfile(uuid,name);
        var p=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet,PacketSendListener callback,boolean flush){}
        };
        var channel=new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,p,CommonListenerCookie.createInitial(profile,false));
        return p;
    }
    static ServerPlayer guest;
    static int stage,start;
    static UUID id,identity;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void click(ServerPlayer p,BlockPos target,boolean remove){
        double x=target.getX()+.5,y=target.getY()+2.2,z=target.getZ()+.5;
        p.connection.teleport(x,y,z,0,90);
        p.setXRot(90);p.setYRot(0);p.setYHeadRot(0);
        var hit=RoomEditor.looking(p);check(hit.getBlockPos().equals(target),"Fixture aim "+target+" hit "+hit.getBlockPos()+" at "+p.position()+" direction "+p.getViewVector(1));
        if(remove)NeoForge.EVENT_BUS.post(new PlayerInteractEvent.LeftClickBlock(p,target,hit.getDirection(),PlayerInteractEvent.LeftClickBlock.Action.START));
        else NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,target,hit));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        try{step(event);}catch(Throwable error){Files.writeString(Path.of("FAILED"),error.toString());throw error;}
    }
    static ServerPlayer builder;
    static BlockPos log;
    static boolean denyCell;
    @SubscribeEvent public static void claim(StorageAccessEvent event){
        if(denyCell && event.position.equals(log) && PocketService.inPocket(event.level))event.setCanceled(true);
    }
    static void side(ServerPlayer p,BlockPos support){
        double x=support.getX()-2.5,y=support.getY(),z=support.getZ()+.5;
        float pitch=(float)Math.toDegrees(Math.atan2(p.getEyeHeight()-.5,2.5));
        p.connection.teleport(x,y,z,-90,pitch);p.setYRot(-90);p.setXRot(pitch);p.setYHeadRot(-90);
        var hit=RoomEditor.looking(p);
        check(hit.getBlockPos().equals(support) && hit.getDirection()==Direction.WEST,"Side ray: "+hit);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,support,hit));
    }
    static boolean flag(String id){return Files.exists(Path.of("../Client/"+id));}
    static void step(ServerTickEvent.Post event)throws Exception{
        var server=event.getServer();
        var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=true;
        if(stage==0){
            builder=join(server,"NativeBuilder",UUID.randomUUID());
            var home=server.overworld();builder.teleportTo(home,3.5,81,3.5,180,0);
            for(int x=0;x<7;x++)for(int z=0;z<7;z++)home.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
            home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());home.setBlockAndUpdate(DOOR,PocketContent.ENTRANCE.get().defaultBlockState());
            var hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=builder.getUUID();
            var door=(PocketContent.EntranceEntity)home.getBlockEntity(DOOR);door.owner=builder.getUUID();
            var tablet=new ItemStack(Content.CUPID_TABLET.get());var tag=BagInventory.data(tablet);
            tag.putUUID("HubId",hub.storageIdentity());tag.putLong("HubPos",HUB.asLong());tag.putString("HubDimension","minecraft:overworld");BagInventory.data(tablet,tag);
            builder.setItemInHand(InteractionHand.MAIN_HAND,tablet);PocketService.link(builder,door,tablet);id=door.pocket;PocketService.enter(builder,door);stage++;return;
        }
        if(!PocketService.inPocket(builder.level()) || !RoomEditor.active(builder))return;
        var level=builder.serverLevel();var room=PocketService.ledger(server).get(id);
        var spot=new BlockPos(room.x()+9,65,room.z()+15);
        if(stage==1){
            log=spot.above();denyCell=true;place(spot,Items.OAK_DOOR);
            check(level.isEmptyBlock(spot)&&level.isEmptyBlock(log)&&builder.getMainHandItem().getCount()==1,"Denied upper door half lost item or placed lower half");
            denyCell=false;place(spot,Items.OAK_DOOR);
            check(level.getBlockState(spot).is(Blocks.OAK_DOOR)&&level.getBlockState(log).is(Blocks.OAK_DOOR)&&builder.getMainHandItem().isEmpty(),"Door parts and cost");
            check(builder.gameMode.destroyBlock(spot)&&level.isEmptyBlock(log),"Paired door removal");stage++;
        }else if(stage==2){
            place(spot,Items.BARREL);check(level.getBlockEntity(spot) instanceof net.minecraft.world.Container,"Normal barrel placement");
            var inv=(net.minecraft.world.Container)level.getBlockEntity(spot);inv.setItem(0,new ItemStack(Items.DIAMOND));
            check(!builder.gameMode.destroyBlock(spot)&&inv.getItem(0).is(Items.DIAMOND),"Filled container protection");
            inv.removeItemNoUpdate(0);check(builder.gameMode.destroyBlock(spot),"Empty container removal");
            stage++;
        }else if(stage==3){
            place(spot,MOD_ITEM.get());check(level.getBlockState(spot).is(MOD_BLOCK.get())&&builder.getMainHandItem().isEmpty(),"Third-party BlockItem");
            check(builder.gameMode.destroyBlock(spot),"Modded block break");
            Files.writeString(Path.of("checks.txt"),"PASS native door placement, multi-cell claim rollback and item conservation, paired break, filled/empty barrel protection, external mod block placement and break.\n");
            stage=99;server.halt(false);
        }
    }
    static void place(BlockPos spot,net.minecraft.world.item.Item item){
        builder.connection.teleport(spot.getX()+2.5,spot.getY(),spot.getZ()+.5,90,20);
        builder.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item));
        var hit=new BlockHitResult(Vec3.atBottomCenterOf(spot),Direction.UP,spot.below(),false);
        builder.gameMode.useItemOn(builder,builder.serverLevel(),builder.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
    }
}
