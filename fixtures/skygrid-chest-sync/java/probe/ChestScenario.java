package probe;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("chestsyncprobe")
@EventBusSubscriber(modid="chestsyncprobe")
public final class ChestScenario {
    static final BlockPos CAM=new BlockPos(5,81,3);
    static ItemStack TOKEN;
    static final int VIEW=BasePermissions.VIEW, VISIT=BasePermissions.VISIT, BUILD=BasePermissions.BUILD, ALL=BasePermissions.ALL;
    static String phase, label;
    static int stage, start, now;
    static ServerPlayer owner, builder, visitor, fresh;
    static UUID roomId, labelledId;
    static PocketLedger.Pocket room;
    static StoreMenu oldMenu;
    static PocketView oldView;
    static Packets.Action stale;
    static final StringBuilder evidence=new StringBuilder();
    static BlockPos pos(int x,int z){return new BlockPos(room.x()+x,65,room.z()+z);}
    static BlockPos oldVanilla(){return pos(9,17);}
    static BlockPos oldLabel(){return pos(12,17);}
    static BlockPos left(){return pos(0,0);}
    static BlockPos right(){return pos(1,0);}
    static BlockPos moved(){return pos(31,15);}
    static ItemStack token(int n){
        var item=new ItemStack(Items.AMETHYST_SHARD,n);
        item.set(DataComponents.CUSTOM_NAME,Component.literal("Skygrid specimen"));
        return item;
    }
    static void require(boolean ok,String error){if(!ok)throw new AssertionError(error);}
    static void passed(String detail){evidence.append("PASS ").append(detail).append('\n');}
    static void next(int target,String detail)throws Exception{
        stage=target;start=now;label=detail;Files.writeString(Path.of("progress.txt"),phase+" stage "+stage+": "+detail+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    static void request(String verb)throws Exception{Files.writeString(Path.of("request"),verb);}
    static boolean sent(String verb){return Files.isRegularFile(Path.of("sent-"+verb));}
    static void finish()throws Exception{Files.writeString(Path.of(phase+".pass"),evidence);stage=999;}
    static Container container(ServerLevel l,BlockPos at){return (Container)l.getBlockEntity(at);}
    static List<Container> banks(ServerPlayer p,PocketLeases.Kind kind)throws Exception{return new PocketView(p,room,kind).containers();}
    static int storageSize(List<Container> list){return list.stream().mapToInt(Container::getContainerSize).sum();}
    static int tokens(Container c){int n=0;for(int i=0;i<c.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(c.getItem(i),TOKEN))n+=c.getItem(i).getCount();return n;}
    static int tokens(List<Container> list){return list.stream().mapToInt(ChestScenario::tokens).sum();}
    static StoreMenu menu(ServerPlayer p){return p.containerMenu instanceof StoreMenu m && m.hasPocket(roomId)?m:null;}
    static void nearDoor(ServerPlayer p){p.teleportTo(p.getServer().overworld(),3.5,81,3.5,180,0);}
    static void remote(ServerPlayer p){
        Actors.standardTablet(p);
        p.gameMode.useItem(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND);
    }
    static void ensureTablet(ServerPlayer p){
        Actors.standardTablet(p);require(BagInventory.data(p.getMainHandItem()).getUUID("PocketId").equals(roomId),"Lost saved ordinary tablet binding");
    }
    static void reloadIds(MinecraftServer server)throws Exception{
        var ids=Files.readAllLines(Path.of("ids.txt"));roomId=UUID.fromString(ids.get(0));labelledId=UUID.fromString(ids.get(1));room=PocketService.ledger(server).get(roomId);
        require(room!=null && owner.getUUID().equals(room.owner()),"Saved room owner");
    }
    static void legacyData(ServerLevel level){
        var labelled=(StorageEntity)level.getBlockEntity(oldLabel());
        require(labelled!=null && labelled.storageIdentity().equals(labelledId) && labelled.getItem(3).is(Items.EMERALD) && labelled.getItem(3).getCount()==7,"Legacy labelled chest UUID / emeralds");
        var chest=container(level,oldVanilla());
        require(chest instanceof ChestBlockEntity && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount()==11,"Legacy vanilla diamonds");
    }
    static void openEntered(ServerPlayer p){Actors.enter(p,Actors.DOOR);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception{
        if(stage==999 || Files.isRegularFile(Path.of("FAILED")))return;
        try{
            var server=event.getServer();now=server.getTickCount();
            if(phase==null){phase=Files.readString(Path.of("phase.txt")).trim();TOKEN=token(13);}
            if(now<40)return;
            var policy=ExpansionRules.get();policy.pocketStorage=true;policy.pocketEditing=true;policy.pocketSessionTicks=2400;
            if(phase.equals("legacy"))legacy(server);else if(phase.equals("upgrade"))upgrade(server);else if(phase.equals("reload"))reload(server);else throw new AssertionError(phase);
            if(stage!=0 && stage!=999 && now-start>1300)throw new AssertionError("Timed out at "+label);
        }catch(Throwable error){
            var out=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(out));
            Files.writeString(Path.of("FAILED"),phase+" stage "+stage+" ("+label+")\n"+out);throw error;
        }
    }
    static void legacy(MinecraftServer server)throws Exception{
        if(stage==0){
            owner=Actors.join(server,"ChestOwner");Actors.setup(server,owner,Actors.HUB,Actors.DOOR);
            roomId=BagInventory.data(owner.getMainHandItem()).getUUID("PocketId");openEntered(owner);next(1,"CCTV.2 first visit");
        }else if(stage==1 && Actors.inside(owner)){
            room=PocketService.ledger(server).get(roomId);
            Actors.place(owner,oldVanilla(),new ItemStack(Items.CHEST),true);
            Actors.place(owner,oldLabel(),new ItemStack(Content.CRATE.get()),true);
            var level=owner.serverLevel();var labelled=(StorageEntity)level.getBlockEntity(oldLabel());labelledId=labelled.storageIdentity();
            labelled.setItem(3,new ItemStack(Items.EMERALD,7));container(level,oldVanilla()).setItem(0,new ItemStack(Items.DIAMOND,11));
            require(PocketRoomLayout.present(level,room).size()==5,"Expected four legacy starters plus one placed labelled chest");
            legacyData(level);PocketService.recover(owner);next(2,"Write the legacy world and bound tablet");
        }else if(stage==2 && !Actors.inside(owner) && PocketService.lease(owner.getUUID())==null){
            ensureTablet(owner);Files.writeString(Path.of("ids.txt"),roomId+"\n"+labelledId+"\n");
            server.getPlayerList().saveAll();passed("CCTV.2 created a real legacy room, four starters, a labelled chest with seven emeralds, a vanilla chest with eleven diamonds and an ordinary linked tablet.");finish();
        }
    }
    static void upgrade(MinecraftServer server)throws Exception{
        var home=server.overworld();var inside=PocketService.dimension(server);
        if(stage==0){
            owner=server.getPlayerList().getPlayer(Actors.uuid("ChestOwner"));if(owner==null || owner.tickCount<40)return;
            builder=server.getPlayerList().getPlayer(Actors.uuid("ChestBuilder"));if(builder==null || builder.tickCount<30)return;
            Actors.survival(owner);Actors.survival(builder);reloadIds(server);ensureTablet(owner);nearDoor(owner);
            owner.getInventory().selected=0;
            Actors.place(owner,CAM,new ItemStack(CameraDevices.SKY_CAMERA.get()),true);
            openEntered(owner);next(1,"Migrate existing room");
        }else if(stage==1 && Actors.inside(owner)){
            legacyData(inside);var before=banks(owner,PocketLeases.Kind.VISIT);
            require(storageSize(before)==297 && before.contains(container(inside,oldVanilla())),"Expected 270 labelled slots plus 27 migrated vanilla slots");
            require(!SkyCeiling.enabled(owner,roomId),"Ceiling enabled before selection");
            SkyCeiling.set(owner,roomId,true);require(!SkyCeiling.enabled(owner,roomId),"Placing an outdoor Sky Camera implicitly linked its feed");
            Actors.place(owner,left(),new ItemStack(Items.CHEST,2),true);
            Actors.place(owner,right(),owner.getMainHandItem(),true);
            require(inside.getBlockState(left()).getValue(ChestBlock.TYPE)!=ChestType.SINGLE && inside.getBlockState(right()).getValue(ChestBlock.TYPE)!=ChestType.SINGLE,"Vanilla double chest failed to join");
            var after=banks(owner,PocketLeases.Kind.VISIT);
            require(storageSize(after)==351 && Collections.frequency(after,container(inside,left()))==1 && Collections.frequency(after,container(inside,right()))==1,"Duplicate/missing double chest halves");
            Actors.place(owner,pos(16,24),new ItemStack(Items.CHEST),false);
            Actors.place(owner,pos(31,31),new ItemStack(Items.COBBLESTONE),true);
            var hub=(StorageEntity)home.getBlockEntity(Actors.HUB);
            visitor=Actors.join(server,"ChestVisitor");
            hub.setMember(builder.getUUID(),VISIT|BUILD);hub.setMember(visitor.getUUID(),VISIT);
            nearDoor(builder);nearDoor(visitor);openEntered(builder);openEntered(visitor);
            passed("Migrated the actual CCTV.2 room and IDs. Two normal Survival chest clicks made a 54-slot double in a corner, both halves counted exactly once. Opposite corner usable and landing protected.");
            next(2,"Visit/Build separation");
        }else if(stage==2 && Actors.inside(builder) && Actors.inside(visitor)){
            Actors.place(builder,pos(0,10),new ItemStack(Items.OAK_PLANKS),true);
            var target=pos(0,12);
            double z=target.getZ()+2.7;
            double pitch=Math.toDegrees(Math.atan2(builder.getEyeHeight(),2.2));
            builder.getInventory().selected=0;Actors.held(builder,new ItemStack(Items.CHEST));
            builder.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(0));builder.inventoryMenu.broadcastChanges();
            builder.connection.teleport(target.getX()+.5,65,z,180,(float)pitch);
            Files.writeString(Path.of("guest-place"),Long.toString(target.below().asLong()));
            next(20,"Second connected client places chest with a normal raycast");
        }else if(stage==20 && Files.isRegularFile(Path.of("guest-placed")) && inside.getBlockState(pos(0,12)).is(Blocks.CHEST)){
            require(builder.getMainHandItem().isEmpty(),"Second client placed a chest without spending one item");
            Actors.place(visitor,pos(0,13),new ItemStack(Items.CHEST),false);
            builder.getInventory().selected=0;Actors.held(builder,ItemStack.EMPTY);Actors.useTop(builder,oldVanilla());
            require(builder.containerMenu==builder.inventoryMenu,"Build-only guest opened nonempty vanilla storage");
            Actors.held(visitor,ItemStack.EMPTY);Actors.useTop(visitor,right());
            require(visitor.containerMenu==visitor.inventoryMenu,"Visitor opened a double chest");
            require(!builder.gameMode.destroyBlock(oldVanilla()) && container(inside,oldVanilla()).getItem(0).getCount()==11,"Build-only guest broke filled chest");
            var hub=(StorageEntity)home.getBlockEntity(Actors.HUB);hub.setMember(builder.getUUID(),ALL);
            container(inside,right()).setItem(0,new ItemStack(Items.LAPIS_LAZULI,2));container(inside,left()).setItem(0,new ItemStack(Items.LAPIS_LAZULI,2));
            Actors.held(builder,new ItemStack(Items.OAK_PLANKS));Actors.useTop(builder,right());
            require(builder.containerMenu instanceof net.minecraft.world.inventory.ChestMenu && builder.containerMenu.getSlot(0).getItem().is(Items.LAPIS_LAZULI) && builder.getMainHandItem().getCount()==1,
                "Authorized player holding a block could not open the physical double chest");
            var physical=builder.containerMenu;hub.setMember(builder.getUUID(),VISIT|BUILD);
            physical.clicked(0,0,net.minecraft.world.inventory.ClickType.PICKUP,builder);
            require(physical.getCarried().isEmpty() && builder.containerMenu==builder.inventoryMenu
                && container(inside,left()).getItem(0).getCount()==2 && container(inside,right()).getItem(0).getCount()==2,"Mid-menu revocation leaked a physical stack");
            container(inside,left()).setItem(0,ItemStack.EMPTY);container(inside,right()).setItem(0,ItemStack.EMPTY);
            hub.setMember(builder.getUUID(),ALL);
            passed("Direct double-chest interaction with a held block opened both halves for an authorized guest. Revoking storage rights immediately before a slot click closed it without moving any items.");
            var copied=owner.getInventory().getItem(8).copy();visitor.getInventory().setItem(8,copied);
            PocketService.recover(visitor);PocketService.recover(owner);
            passed("A second independently connected nonoperator Minecraft client placed a wall-adjacent empty chest with a normal Survival use packet, without a tablet or editor toggle. Visit-only placement and access to contents by either guest were denied.");
            next(3,"External linked tablet and negative camera case");
        }else if(stage==3 && !Actors.inside(owner) && !Actors.inside(visitor) && PocketService.lease(owner.getUUID())==null && PocketService.lease(visitor.getUUID())==null){
            nearDoor(owner);Actors.standardTablet(owner);owner.setShiftKeyDown(true);Actors.useTop(owner,CAM);owner.setShiftKeyDown(false);openEntered(owner);
            var hub=(StorageEntity)home.getBlockEntity(Actors.HUB);hub.setMember(visitor.getUUID(),0);nearDoor(visitor);remote(visitor);
            next(4,"Confirm explicit camera selection");
        }else if(stage==4 && Actors.inside(owner)){
            require(PocketService.lease(visitor.getUUID())==null && menu(visitor)==null,"Unauthorized copied tablet opened private bank");
            SkyCeiling.set(owner,roomId,true);require(SkyCeiling.enabled(owner,roomId),"Explicit outdoor camera selection did not permit the owner's live ceiling");SkyCeiling.set(owner,roomId,false);
            passed("Placing a Sky Camera left the ceiling unlinked. Explicit owner tablet selection outdoors enabled the live-ceiling choice on returning. A copied tablet with no grant exposed no pocket menu.");
            for(var c:banks(owner,PocketLeases.Kind.VISIT)){
                for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())c.setItem(i,new ItemStack(Items.COBBLESTONE,64));
            }
            PocketService.recover(owner);next(5,"Open ordinary tablet from outside using real client");
        }else if(stage==5 && !Actors.inside(owner) && PocketService.lease(owner.getUUID())==null){
            nearDoor(owner);Actors.standardTablet(owner);owner.getInventory().setItem(9,TOKEN.copy());owner.inventoryMenu.broadcastChanges();
            request("open");next(6,"Open via real client");
        }else if(stage==6 && menu(owner)!=null){
            require(!Actors.inside(owner) && menu(owner).capacity()==378,"Expected 270 labelled + 108 ordinary chest slots on outside tablet, got "+menu(owner).capacity());
            request("offerFull");next(7,"Real network deposit with every destination full");
        }else if(stage==7 && sent("offerFull") && now-start>=35){
            var banks=banks(owner,PocketLeases.Kind.REMOTE);require(tokens(banks)==0 && tokens(owner.getInventory())==13,"Rejected full deposit lost or duplicated items");
            container(inside,right()).setItem(4,ItemStack.EMPTY);request("offerSpace");next(8,"Real network stack deposit into the only free slot in second half");
        }else if(stage==8 && sent("offerSpace") && tokens(owner.getInventory())==0){
            var banks=banks(owner,PocketLeases.Kind.REMOTE);require(tokens(banks)==13 && tokens(container(inside,right()))==13,"Did not reach the specific vanilla double half");
            for(int i=0;i<36;i++)if(owner.getInventory().getItem(i).isEmpty())owner.getInventory().setItem(i,new ItemStack(Items.STONE,64));
            request("find");next(9,"Filter to named item");
        }else if(stage==9 && sent("find") && menu(owner)!=null && menu(owner).getSlot(0).getItem().is(Items.AMETHYST_SHARD)){
            request("takeFull");next(10,"Attempt withdrawal with full Survival inventory");
        }else if(stage==10 && sent("takeFull") && now-start>=35){
            require(tokens(owner.getInventory())==0 && tokens(container(inside,right()))==13,"Full player inventory changed stored item");
            owner.getInventory().setItem(9,ItemStack.EMPTY);request("takeSpace");next(11,"Withdraw unchanged named stack");
        }else if(stage==11 && sent("takeSpace") && tokens(owner.getInventory())==13){
            require(tokens(banks(owner,PocketLeases.Kind.REMOTE))==0 && owner.getInventory().getItem(9).getCount()==13,"Real withdraw lost metadata or duplicated stack");
            request("done");oldMenu=menu(owner);oldView=new PocketView(owner,room,PocketLeases.Kind.REMOTE);
            var slot=slotOf(oldMenu,9);stale=new Packets.Action(oldMenu.containerId,oldMenu.session(),oldMenu.revision(),"slot","",slot);
            // Synthetic filler can now be removed. Keep the original legacy items and a unique item on the surviving half.
            for(var bank:oldView.containers())for(int i=0;i<bank.getContainerSize();i++)if(bank.getItem(i).is(Items.COBBLESTONE))bank.setItem(i,ItemStack.EMPTY);
            container(inside,left()).setItem(6,new ItemStack(Items.GOLD_INGOT,5));
            passed("A networked Minecraft client opened its ordinary linked tablet outdoors. Its real slot packets kept all 13 named shards on both full-inventory rejections, then deposited into and withdrew from the second half without loss or duplication.");
            next(12,"Break half and move it to the other wall while previous tablet is open");
        }else if(stage==12){
            var saved=container(inside,right());Actors.held(builder,new ItemStack(Items.IRON_AXE));
            builder.connection.teleport(left().getX()+2.5,65,left().getZ()+2.5,135,0);
            require(!builder.gameMode.destroyBlock(left()) && container(inside,left()).getItem(6).getCount()==5,"An authorized builder scattered a filled target by breaking it");
            require(oldView.valid(),"Remote view expired before topology changed");
            int before=countDrops(inside,right());require(builder.gameMode.destroyBlock(right()),"Authorized empty-half removal");
            require(inside.isEmptyBlock(right()) && inside.getBlockState(left()).getValue(ChestBlock.TYPE)==ChestType.SINGLE && container(inside,left()).getItem(6).getCount()==5,"Removed one half damaged surviving half");
            require(countDrops(inside,right())==before+1,"Normal break did not return exactly one chest");
            require(!oldView.valid(),"Pre-removal view still authorized stale chest");
            oldMenu.action(stale);require(tokens(owner.getInventory())==13&&tokens(saved)==0,"Stale packet consumed or wrote detached items");
            var drop=inside.getEntitiesOfClass(ItemEntity.class,new AABB(right()).inflate(2),e->e.getItem().is(Items.CHEST)).getFirst();
            Actors.held(builder,ItemStack.EMPTY);drop.setNoPickUpDelay();drop.playerTouch(builder);
            int available=-1;for(int i=0;i<36;i++)if(builder.getInventory().getItem(i).is(Items.CHEST)){available=i;break;}
            require(available>=0,"Survival chest pickup");
            var picked=builder.getInventory().removeItem(available,1);Actors.place(builder,moved(),picked,true);
            require(container(inside,left()).getItem(6).getCount()==5 && container(inside,moved()).isEmpty(),"Move duplicated stored contents");
            owner.closeContainer();next(13,"Capture moved chest and stop first candidate boot");
        }else if(stage==13 && PocketService.lease(owner.getUUID())==null){
            if(Actors.inside(builder)){PocketService.recover(builder);return;}
            ensureTablet(owner);server.getPlayerList().saveAll();
            passed("Removing one half produced one vanilla chest drop. The pre-removal view rejected a stale packet; five ingots stayed in the surviving half. The recovered chest was placed at the opposite wall empty.");finish();
        }
    }
    static int slotOf(StoreMenu menu,int invIndex){
        for(int i=54;i<90;i++)if(menu.getSlot(i).getContainerSlot()==invIndex)return i;
        throw new AssertionError("Missing real inventory slot "+invIndex);
    }
    static int countDrops(ServerLevel level,BlockPos pos){
        return level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(2),e->e.getItem().is(Items.CHEST)).stream().mapToInt(e->e.getItem().getCount()).sum();
    }
    static void reload(MinecraftServer server)throws Exception{
        if(stage==0){
            owner=Actors.join(server,"ChestOwner");reloadIds(server);ensureTablet(owner);nearDoor(owner);remote(owner);next(1,"Post-restart external tablet");
        }else if(stage==1 && menu(owner)!=null){
            var inside=PocketService.dimension(server);legacyData(inside);var banks=banks(owner,PocketLeases.Kind.REMOTE);
            require(storageSize(banks)==378 && menu(owner).capacity()==378 && container(inside,moved()) instanceof ChestBlockEntity && inside.isEmptyBlock(right()),"Stale topology after restart");
            require(tokens(owner.getInventory())==13 && tokens(banks)==0 && container(inside,left()).getItem(6).getCount()==5,"Restart conservation");
            passed("Candidate restart recovered the exact existing binding, labelled chest identity, legacy contents, named withdrawn stack and moved vanilla chest topology.");
            owner.closeContainer();fresh=Actors.join(server,"FirstTimer");
            var h=Actors.HUB.offset(12,0,0);var door=Actors.DOOR.offset(12,0,0);Actors.setup(server,fresh,h,door);
            roomId=BagInventory.data(fresh.getMainHandItem()).getUUID("PocketId");Actors.enter(fresh,door);next(2,"First-time empty room");
        }else if(stage==2 && Actors.inside(fresh)){
            room=PocketService.ledger(server).get(roomId);var empty=banks(fresh,PocketLeases.Kind.VISIT);
            require(empty.isEmpty(),"New rooms still contain mandatory starter chests");
            Actors.standardTablet(fresh);fresh.gameMode.useItem(fresh,fresh.serverLevel(),fresh.getMainHandItem(),InteractionHand.MAIN_HAND);
            require(menu(fresh)!=null && menu(fresh).capacity()==0,"Empty room failed to open a zero-capacity linked tablet");
            fresh.closeContainer();
            Actors.place(fresh,pos(31,0),new ItemStack(Items.CHEST),true);
            require(storageSize(banks(fresh,PocketLeases.Kind.VISIT))==27,"Fresh room first corner chest was not automatically usable");
            passed("A new first-time Survival room started at zero. Its ordinary linked tablet opened with zero slots; the first corner chest immediately contributed 27.");finish();
        }
    }
}
