package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tschipp.carryon.common.carry.*;
import xaero.pac.common.server.api.OpenPACServerAPI;
import java.nio.file.*;
import java.util.*;

/** Isolated beta.6 suite. Editing commands and chest actions use actual connected Minecraft clients. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class RoomProbe {
    private static MinecraftServer server;
    private static StorageEntity hub,added;
    private static PocketContent.EntranceEntity entrance;
    private static UUID id;
    private static int phase,tick,since,sequence;
    private static boolean running;
    private static final Map<UUID,Integer> ACKS=new HashMap<>();
    private static final int RIGHTS=BasePermissions.VISIT|BasePermissions.BUILD|BasePermissions.LEGACY;
    private static final BlockPos HUB=new BlockPos(1,81,1),ENTRANCE=new BlockPos(3,81,1);
    public static boolean active(){return Boolean.getBoolean("anya.roomAcceptance");}
    private static ServerPlayer a(){return server.getPlayerList().getPlayerByName("AnyaQAAlpha");}
    private static ServerPlayer b(){return server.getPlayerList().getPlayerByName("AnyaQABeta");}
    private static PocketLedger.Pocket room()throws Exception{return PocketService.ledger(server).get(id);}
    private static BlockPos at(int x,int y,int z)throws Exception{return new BlockPos(room().x()+x,y,room().z()+z);}
    private static void check(boolean value,String message){Acceptance.require(value,message);}
    private static void log(String message){AnyaStorage.LOG.info("ANYA_ROOM_ASSERT {}",message);}
    private static int count(ServerPlayer p,Item item){int n=0;for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static boolean inside(ServerPlayer p){return p!=null&&PocketService.inPocket(p.level());}
    public static void ack(ServerPlayer p,Wire.Signal signal){if(signal.sequence()==sequence&&signal.action().equals("done"))ACKS.put(p.getUUID(),sequence);}
    private static boolean done(ServerPlayer p){return p!=null&&Objects.equals(ACKS.get(p.getUUID()),sequence);}
    private static void send(ServerPlayer p,String command){sequence++;ACKS.clear();PacketDistributor.sendToPlayer(p,new Wire.Signal(sequence,command));since=tick;}
    private static void next(){phase++;since=tick;}
    private static void held(ServerPlayer p,ItemStack stack){p.getInventory().selected=0;p.getInventory().setItem(0,stack);p.inventoryMenu.broadcastChanges();}
    /** Fixture camera placement only. Commands still calculate and validate their target on the server. */
    private static BlockHitResult aim(ServerPlayer p,BlockPos target,boolean floor){
        Vec3 point=new Vec3(target.getX()+.5,target.getY()+(floor?.99:.5),target.getZ()+.5);
        double x=target.getX()+.5,y=floor?target.getY()+1:target.getY(),z=target.getZ()+2.5;
        double dy=point.y-(y+p.getEyeHeight());float pitch=(float)-Math.toDegrees(Math.atan2(dy,2));
        p.connection.teleport(x,y,z,180,pitch);p.setYHeadRot(180);
        var hit=RoomEditor.looking(p);check(hit.getBlockPos().equals(target),"Fixture raycast missed intended target: expected="+target+" actual="+hit.getBlockPos()+" face="+hit.getDirection());return hit;
    }
    private static void rejected(ServerPlayer p,BlockHitResult hit,boolean removal,String label)throws Exception{
        BlockPos target=removal?hit.getBlockPos():hit.getBlockPos().relative(hit.getDirection());boolean loaded=p.serverLevel().hasChunkAt(target);
        var state=loaded?p.serverLevel().getBlockState(target):null;var entity=loaded?p.serverLevel().getBlockEntity(target):null;
        var inv=new MainInventory(p);List<ItemStack> before=new ArrayList<>();for(int i=0;i<36;i++)before.add(inv.getItem(i).copy());
        boolean refused=false;
        try{var edit=RoomEditor.prepare(p,hit,removal);refused=!edit.commit().isEmpty();}catch(java.io.IOException|IllegalArgumentException expected){refused=true;}
        check(refused,"Expected rejection: "+label);for(int i=0;i<36;i++)check(Transactions.same(before.get(i),inv.getItem(i)),"Rejected edit changed inventory: "+label);
        if(loaded)check(p.serverLevel().getBlockState(target)==state&&p.serverLevel().getBlockEntity(target)==entity,"Rejected edit changed world: "+label);
        else check(!p.serverLevel().hasChunkAt(target),"Rejected edit loaded target: "+label);
        log("rejected without world/inventory mutation: "+label);
    }
    @SubscribeEvent public static void start(ServerStartedEvent event)throws Exception{
        if(!active())return;server=event.getServer();Acceptance.verifyJar("room-server");
        if(Boolean.getBoolean("anya.roomResume")){resume();return;}
        check(!Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Room acceptance requires a new disposable world");
        ExpansionRules.get().pocketStorage=true;ExpansionRules.get().pocketEditing=true;
        var level=server.overworld();for(int x=-5;x<25;x++)for(int z=-5;z<25;z++)level.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
        level.setDefaultSpawnPos(new BlockPos(12,81,12),180);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        level.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());level.setBlockAndUpdate(ENTRANCE,PocketContent.ENTRANCE.get().defaultBlockState());
        hub=(StorageEntity)level.getBlockEntity(HUB);entrance=(PocketContent.EntranceEntity)level.getBlockEntity(ENTRANCE);
        running=true;Acceptance.marker("server-ready","New room-editing acceptance fixture");
    }
    private static void negatives()throws Exception{
        ServerPlayer p=a();held(p,new ItemStack(Items.CHERRY_PLANKS,3));
        rejected(p,aim(p,at(16,64,24),true),false,"protected landing and exit corridor");
        rejected(p,aim(p,at(0,64,20),true),false,"outside editable inset");
        rejected(p,aim(p,at(6,64,20),true),true,"permanent shell floor");
        rejected(p,aim(p,PocketService.chestPositions(room()).getFirst(),false),true,"original mandatory chest");
        held(p,new ItemStack(Items.HOPPER));rejected(p,aim(p,at(6,64,20),true),false,"unapproved automation block");
        ItemStack named=new ItemStack(Items.CHERRY_PLANKS);named.set(DataComponents.CUSTOM_NAME,Component.literal("Must not strip components"));held(p,named);
        rejected(p,aim(p,at(6,64,20),true),false,"component-bearing input");
        held(p,new ItemStack(Items.CHERRY_PLANKS,3));var hit=aim(p,at(24,64,20),true);
        var old=RoomEditor.prepare(p,hit,false);hub.profileChanged();check(!old.commit().isEmpty()&&!old.commit().isEmpty(),"Stale/replayed construction accepted");
        check(p.level().isEmptyBlock(at(24,65,20))&&count(p,Items.CHERRY_PLANKS)==3,"Stale edit changed material/world");
        var gate=RoomEditor.prepare(p,hit,false);ExpansionRules.get().pocketEditing=false;check(!gate.commit().isEmpty(),"Disabled editing accepted old intent");ExpansionRules.get().pocketEditing=true;
        var claims=OpenPACServerAPI.get(server).getServerClaimsManager();var pos=at(24,65,20);var dim=p.level().dimension().location();
        claims.claim(dim,UUID.fromString("4558b4b4-f88a-45d5-85b4-200000000001"),0,pos.getX()>>4,pos.getZ()>>4,false);
        rejected(p,hit,false,"foreign OPAC construction claim");claims.unclaim(dim,pos.getX()>>4,pos.getZ()>>4);
        var positive=RoomEditor.prepare(p,hit,false);check(positive.commit().isEmpty(),"Owner decoration failed");check(!positive.commit().isEmpty(),"Successful edit replayed");
        check(p.level().getBlockState(pos).is(Blocks.CHERRY_PLANKS)&&count(p,Items.CHERRY_PLANKS)==2,"Represented-block conservation failed");
        rejected(p,aim(p,at(26,64,20),true),false,"same-tick separate edit cooldown");
        log("owner single-use world/inventory conservation; stale policy; disabled gate; real OPAC edit denial; independent-edit cooldown");
        // Forged target coordinates must fail without loading their destination.
        BlockPos far=new BlockPos(1000000,65,1000000);var level=p.serverLevel();check(!level.hasChunkAt(far),"Far fixture target already loaded");
        rejected(p,new BlockHitResult(Vec3.atCenterOf(far),Direction.UP,far,false),false,"forged non-raycast unloaded target");check(!level.hasChunkAt(far),"Rejected edit loaded a distant chunk");
    }
    private static void movers()throws Exception{
        var p=b();var cp=added.getBlockPos();aim(p,cp,false);ItemStack held=p.getMainHandItem().copy();held(p,ItemStack.EMPTY);
        var data=CarryOnDataManager.getCarryData(p);data.setKeyPressed(true);CarryOnDataManager.setCarryData(p,data);
        var click=new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,cp,new BlockHitResult(Vec3.atCenterOf(cp),Direction.UP,cp,false));NeoForge.EVENT_BUS.post(click);
        check(click.isCanceled()&&p.level().getBlockEntity(cp)==added&&!CarryOnDataManager.getCarryData(p).isCarrying(),"Carry On moved optional chest");
        data.setKeyPressed(false);CarryOnDataManager.setCarryData(p,data);held(p,held);p.closeContainer();
        var mining=new BlockEvent.BreakEvent(p.level(),cp,p.level().getBlockState(cp),p);NeoForge.EVENT_BUS.post(mining);check(mining.isCanceled(),"Vanilla/mover break event not denied");
        check(added.getSlotsForFace(Direction.DOWN).length==0&&!added.canTakeItemThroughFace(0,added.getItem(0),Direction.DOWN),"Sided automation bypass");
        check(p.serverLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,cp,(Direction)null)==null,"Item-handler capability exposed");
        p.level().explode(null,cp.getX()+.5,65.5,cp.getZ()+.5,2.0f,Level.ExplosionInteraction.BLOCK);
        check(p.level().getBlockEntity(cp)==added&&added.getItem(0).getCount()==13,"Explosion altered optional chest or contents");
        log("real Carry On event bus, break event, empty sided ports, absent item-handler capability and real explosion preserve optional chest");
    }
    private static void layoutChanges()throws Exception{
        ServerPlayer owner=a();BlockPos pad=PocketRoomLayout.pads(room()).get(1);
        var view=new PocketView(b(),room(),PocketLeases.Kind.VISIT);var sink=new SimpleContainer(1);
        var stale=new Transactions.Plan(List.of(added,sink),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));check(stale.move(added,0,List.of(sink),13)==13,"Stale fixture plan missing sample");
        held(owner,new ItemStack(Content.CRATE.get()));check(RoomEditor.prepare(owner,aim(owner,pad.below(),true),false).commit().isEmpty(),"Second optional chest placement failed");
        check(!stale.commit(server).isEmpty()&&sink.isEmpty()&&added.getItem(0).getCount()==13,"Changed registry accepted old transfer");
        check(PocketRoomLayout.present(owner.serverLevel(),room()).size()==6,"Optional capacity discovery failed");
        List<ItemStack> before=new ArrayList<>();for(int i=0;i<36;i++){before.add(owner.getInventory().getItem(i).copy());owner.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));}
        rejected(owner,aim(owner,pad,false),true,"full player inventory removing empty optional chest");
        for(int i=0;i<36;i++)owner.getInventory().setItem(i,before.get(i));owner.inventoryMenu.broadcastChanges();
        int cap=ExpansionRules.get().pocketContainers;ExpansionRules.get().pocketContainers=4;
        check(PocketRoomLayout.present(owner.serverLevel(),room()).size()==6,"Reduced placement cap hid existing storage");
        ExpansionRules.get().pocketContainers=cap;
        log("layout change invalidates old transfer; full-inventory removal refuses; reduced cap never hides existing contents");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
        if(!running||event.getServer()!=server)return;tick++;
        try{
            if(tick-since>1800)throw new IllegalStateException("Room phase timeout "+phase);
            switch(phase){
                case 0 -> {if(a()!=null&&b()!=null){
                    hub.owner=a().getUUID();entrance.owner=a().getUUID();hub.setMember(b().getUUID(),BasePermissions.VISIT);
                    a().teleportTo(server.overworld(),3.5,81,3.5,180,0);b().teleportTo(server.overworld(),4.5,81,3.5,180,0);
                    a().getInventory().clearContent();b().getInventory().clearContent();
                    ItemStack remote=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(remote);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",HUB.asLong());BagInventory.data(remote,t);
                    a().getInventory().setItem(0,remote);PocketService.link(a(),entrance,remote);id=entrance.pocket;check(id!=null,"Link failed");a().getInventory().setItem(8,remote.copy());held(a(),ItemStack.EMPTY);
                    send(a(),"block 3 81 1");next();
                }}
                case 1 -> {if(done(a())&&inside(a())){send(b(),"block 3 81 1");next();}}
                case 2 -> {if(done(b())&&inside(b())){negatives();held(b(),new ItemStack(Items.CHERRY_PLANKS,3));aim(b(),at(6,64,20),true);send(b(),"command anya pocket build place");next();}}
                case 3 -> {if(done(b())){check(b().level().isEmptyBlock(at(6,65,20))&&count(b(),Items.CHERRY_PLANKS)==3,"Visit-only builder changed world/items");hub.setMember(b().getUUID(),BasePermissions.VISIT|BasePermissions.BUILD);send(b(),"command anya pocket build place");next();}}
                case 4 -> {if(done(b())){check(b().level().getBlockState(at(6,65,20)).is(Blocks.CHERRY_PLANKS)&&count(b(),Items.CHERRY_PLANKS)==2,"Delegated real-client placement failed");send(b(),"photo delegated-room-build");next();}}
                case 5 -> {if(done(b())){held(b(),new ItemStack(Content.CRATE.get()));aim(b(),PocketRoomLayout.pads(room()).getFirst().below(),true);send(b(),"command anya pocket build place");next();}}
                case 6 -> {if(done(b())){check(b().level().isEmptyBlock(PocketRoomLayout.pads(room()).getFirst())&&count(b(),Content.CRATE.get().asItem())==1,"Build-only guest placed storage chest");hub.setMember(b().getUUID(),RIGHTS);send(b(),"command anya pocket build place");next();}}
                case 7 -> {if(done(b())){var cp=PocketRoomLayout.pads(room()).getFirst();added=(StorageEntity)b().level().getBlockEntity(cp);check(added!=null&&id.equals(added.pocketId)&&a().getUUID().equals(added.owner)&&PocketRoomLayout.present(b().serverLevel(),room()).size()==5,"Pad identity/owner/layout failed");ItemStack sample=new ItemStack(Items.EMERALD,13);sample.set(DataComponents.CUSTOM_NAME,Component.literal("Room acceptance emeralds"));b().getInventory().setItem(9,sample);b().inventoryMenu.broadcastChanges();send(b(),"block "+cp.getX()+" 65 "+cp.getZ());next();}}
                case 8 -> {if(done(b())){check(b().containerMenu instanceof StoreMenu m&&m.capacity()==54,"Added physical chest did not open");send(b(),"deposit");next();}}
                case 9 -> {if(done(b())){check(added.getItem(0).getCount()==13&&count(b(),Items.EMERALD)==0,"Added chest deposit lost or duplicated sample");b().closeContainer();aim(b(),added.getBlockPos(),false);send(b(),"command anya pocket build remove");next();}}
                case 10 -> {if(done(b())){check(b().level().getBlockEntity(added.getBlockPos())==added&&added.getItem(0).getCount()==13,"Occupied optional chest was removed");movers();layoutChanges();next();}}
                case 11 -> {if(tick-since>10){var pad=PocketRoomLayout.pads(room()).get(1);check(RoomEditor.prepare(a(),aim(a(),pad,false),true).commit().isEmpty(),"Empty optional chest removal failed");check(a().level().isEmptyBlock(pad)&&count(a(),Content.CRATE.get().asItem())==1,"Empty chest returned incorrectly");held(b(),new ItemStack(Items.CHERRY_PLANKS,2));var planned=RoomEditor.prepare(b(),aim(b(),at(8,64,20),true),false);hub.setMember(b().getUUID(),BasePermissions.VISIT);check(!planned.commit().isEmpty()&&b().level().isEmptyBlock(at(8,65,20))&&count(b(),Items.CHERRY_PLANKS)==2,"Build revocation accepted stale edit");hub.setMember(b().getUUID(),RIGHTS);aim(b(),at(6,65,20),false);send(b(),"command anya pocket build remove");next();}}
                case 12 -> {if(done(b())){check(b().level().isEmptyBlock(at(6,65,20))&&count(b(),Items.CHERRY_PLANKS)==3,"Decorative removal not conserved");aim(b(),added.getBlockPos(),false);var cp=added.getBlockPos();send(b(),"block "+cp.getX()+" 65 "+cp.getZ());next();}}
                case 13 -> {if(done(b())){
                    StoreMenu.pocket(b(),new PocketView(b(),room(),PocketLeases.Kind.VISIT));
                    check(b().containerMenu instanceof StoreMenu m&&m.capacity()==270,"Combined view did not include the added physical chest");
                    send(b(),"roomnetwork");next();
                }}
                case 14 -> {if(done(b())){Acceptance.marker("kill-beta","Kill visiting room builder with persisted added chest contents");next();}}
                case 15 -> {if(b()==null){Acceptance.marker("restart-beta","Reconnect same room-builder identity");next();}}
                case 16 -> {if(b()!=null&&!inside(b())&&PocketService.ledger(server).returning(b().getUUID())==null&&tick-since>30){check(count(b(),Items.CHERRY_PLANKS)==3&&count(b(),Items.EMERALD)==0&&added.getItem(0).getCount()==13,"Socket recovery changed build materials or stored sample");log("real client SIGKILL/reconnect preserved added chest, build rights and recovered materials");
                    // Obstruct all candidates near the original return but leave hub/entrance identities intact.
                    for(int x=0;x<=6;x++)for(int z=0;z<=6;z++)for(int y=81;y<=87;y++){BlockPos p=new BlockPos(x,y,z);if(!p.equals(HUB)&&!p.equals(ENTRANCE))server.overworld().setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());}
                    a().connection.teleport(room().x()+33.5,65,room().z()+24.5,0,0);next();
                }}
                case 17 -> {if(!inside(a())&&tick-since>10){check(a().getX()>=9&&a().getZ()>=9&&PocketService.ledger(server).returning(a().getUUID())==null,"Unsafe original return did not use safe spawn fallback");log("out-of-bound visitor auto-returned to safe spawn after all original landing candidates were obstructed");next();}}
                case 18 -> {if(tick-since>=650){check(PocketService.ownedTicketRequests()==0,"Room edits leaked ticket requests");var r=room();for(int x=(r.x()-1)>>4;x<=(r.x()+32)>>4;x++)for(int z=(r.z()-1)>>4;z<=(r.z()+32)>>4;z++)check(PocketService.dimension(server).getChunkSource().getChunkNow(x,z)==null,"Edited room chunk retained");
                    Acceptance.marker("room-identities.txt",id+"\n"+hub.storageIdentity()+"\n"+added.storageIdentity()+"\n"+b().getUUID()+"\n");
                    server.saveEverything(true,true,true);running=false;Acceptance.marker("PASSED","Room-editing subset: actual client commands, optional physical chest and inventory safety; server-side negative/API cases; socket interruption; unsafe-return recovery; final unload");AnyaStorage.LOG.info("ANYA_ROOM_NETWORK_OK");server.halt(false);
                }}
            }
        }catch(Throwable error){running=false;Acceptance.marker("FAILED-room","phase="+phase+" "+error);AnyaStorage.LOG.error("ANYA_ROOM_FAILED phase="+phase,error);server.halt(false);}
    }
    private static void resume()throws Exception{
        List<String> ids=Files.readAllLines(Acceptance.control().resolve("room-identities.txt"));id=UUID.fromString(ids.get(0));
        server.overworld().getChunk(0,0);var h=(StorageEntity)server.overworld().getBlockEntity(HUB);var r=room();
        check(h!=null&&h.storageIdentity().equals(UUID.fromString(ids.get(1))),"Restart changed home identity");check(h.memberMask(UUID.fromString(ids.get(3)))==RIGHTS,"Restart changed independent Build rights");
        var level=PocketService.dimension(server);for(var p:PocketService.chestPositions(r))level.getChunk(p.getX()>>4,p.getZ()>>4);for(var p:PocketRoomLayout.pads(r))level.getChunk(p.getX()>>4,p.getZ()>>4);
        var all=PocketRoomLayout.present(level,r);check(all.size()==5,"Restart changed optional chest registry");var chest=all.get(4);
        check(chest.storageIdentity().equals(UUID.fromString(ids.get(2)))&&chest.getItem(0).getCount()==13&&chest.getItem(0).is(Items.EMERALD)&&chest.getItem(0).getHoverName().getString().equals("Room acceptance emeralds"),"Restart changed added chest identity/items/components");
        check(level.isEmptyBlock(new BlockPos(r.x()+6,65,r.z()+20))&&level.getBlockState(new BlockPos(r.x()+24,65,r.z()+20)).is(Blocks.CHERRY_PLANKS),"Restart changed edit outcomes");
        check(PocketService.ownedTicketRequests()==0,"Restart restored a temporary lease");
        Acceptance.marker("ROOM_RESTART_PASSED","Separate installed-server process retained existing home/pocket IDs, independent Build rights, added physical chest, named contents and decor edits; no visitors restored");
        AnyaStorage.LOG.info("ANYA_ROOM_RESTART_OK");server.halt(false);
    }
    private RoomProbe(){}
}
