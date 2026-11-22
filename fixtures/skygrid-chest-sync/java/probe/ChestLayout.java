package probe;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import static probe.ChestScenario.*;

/** Exercise ordinary Survival input on the installed binary and on the candidate in separate fresh worlds. */
final class ChestLayout {
    private static final Map<BlockPos,Integer> markers=new LinkedHashMap<>();
    private static boolean baseline;
    private static int stepTick, expectedCapacity;

    static void tick(MinecraftServer server) throws Exception {
        baseline=phase.equals("layout-baseline");
        if(phase.equals("layout-restart")) { restart(server); return; }
        var inside=PocketService.dimension(server);
        if(stage==0) {
            if(baseline) owner=Actors.join(server,"LayoutOwner");
            else {
                owner=server.getPlayerList().getPlayer(Actors.uuid("ChestOwner"));
                builder=server.getPlayerList().getPlayer(Actors.uuid("ChestBuilder"));
                if(owner==null || builder==null || owner.tickCount<40 || builder.tickCount<40)return;
            }
            Actors.survival(owner);Actors.setup(server,owner,Actors.HUB,Actors.DOOR);
            roomId=BagInventory.data(owner.getMainHandItem()).getUUID("PocketId");
            openEntered(owner);next(1,"Enter fresh room without editor or chest pairing");
        } else if(stage==1 && Actors.inside(owner)) {
            room=PocketService.ledger(server).get(roomId);
            if(!ready(inside,room))return;
            require(banks(owner,PocketLeases.Kind.VISIT).isEmpty(),"Unexpected starter storage");
            vanilla(inside);
            var hub=(StorageEntity)server.overworld().getBlockEntity(Actors.HUB);
            visitor=Actors.join(server,"LayoutVisitor");hub.setMember(visitor.getUUID(),VISIT);
            nearDoor(visitor);openEntered(visitor);
            if(!baseline) {hub.setMember(builder.getUUID(),ALL);nearDoor(builder);openEntered(builder);}
            next(2,"Check permission and seed the original labelled chest");
        } else if(stage==2 && Actors.inside(visitor) && (baseline||Actors.inside(builder))) {
            Actors.place(visitor,pos(4,21),new ItemStack(Items.CHEST),false);
            Actors.place(owner,pos(16,24),new ItemStack(Items.CHEST),false);
            Actors.place(owner,anchor(),new ItemStack(Content.CRATE.get()),true);
            mark(inside,anchor());
            heldCrates(visitor);visitor.setShiftKeyDown(true);click(visitor,anchor(),Direction.NORTH,0);visitor.setShiftKeyDown(false);
            require(inside.isEmptyBlock(anchor().north()) && visitor.getMainHandItem().getCount()==2,
                "Visit-only labelled attachment changed the world or held stack");
            if(baseline && visitor.containerMenu!=visitor.inventoryMenu) {
                var restricted=menu(visitor);
                require(restricted!=null && restricted.privateView() && restricted.capacity()==0 &&
                    restricted.occupiedSlots()==0 && restricted.resultCount()==0 && shown(restricted).isEmpty(),
                    "Visit-only intercepted opening disclosed storage");
                visitor.closeContainer();
            } else require(visitor.containerMenu==visitor.inventoryMenu,"Visit-only attachment opened an inventory");
            checkPhysical(inside);
            passed("A Visit-only guest could neither place a vanilla chest nor attach a labelled one to an occupied chest. Both held items and the original contents were conserved. The reserved landing rejected placement.");
            stepTick=now;
            next(3,"Crouch-click both sides and then the top");
        } else if(stage>=3 && stage<=5 && now-stepTick>=8) {
            var face=List.of(Direction.EAST,Direction.WEST,Direction.UP).get(stage-3);
            labelledFace(owner,inside,face,!baseline);
            stepTick=now;next(stage+1,"Next supported face / labelled placement cadence");
        } else if(stage==6 && now-stepTick>=8) {
            heldCrates(owner);owner.setShiftKeyDown(false);
            click(owner,anchor(),Direction.NORTH,0);
            require(menu(owner)!=null && menu(owner).capacity()==54 && owner.getMainHandItem().getCount()==2,"Standing right-click should open the original labelled inventory");
            owner.closeContainer();
            var first=pos(31,20);Actors.place(owner,first,new ItemStack(Content.CRATE.get()),true);mark(inside,first);
            Actors.place(owner,pos(31,22),new ItemStack(Content.CRATE.get()),false);
            stepTick=now;next(7,"Apply the normal four-tick held-use cadence after blocking a duplicate in the same tick");
        } else if(stage==7 && now-stepTick==4) {
            var second=pos(31,22);Actors.place(owner,second,new ItemStack(Content.CRATE.get()),!baseline);
            if(!baseline)mark(inside,second);
            checkPhysical(inside);
            passed(baseline?"Installed packsettings.1 reproduced three crouched labelled face clicks opening the existing inventory, preserving its stack and leaving the new chest in hand. A separate valid fourth-tick placement was also refused.":"Crouched east, west and top labelled placements each consumed one chest and added an independent 54-slot inventory. Standing use opened the original chest. A second wall placement succeeded four ticks after the first.");
            if(baseline){PocketService.recover(owner);next(11,"Read baseline via ordinary linked tablet");}
            else {
                heldCrates(builder);builder.connection.send(new ClientboundSetCarriedItemPacket(0));builder.inventoryMenu.broadcastChanges();
                Files.writeString(Path.of("layout-shift"),"hold crouch");
                next(8,"Second Minecraft client crouches for labelled placement");
            }
        } else if(stage==8 && builder.isSecondaryUseActive()) {
            aim(builder,anchor(),Direction.SOUTH,0);
            Files.writeString(Path.of("layout-attach"),Long.toString(anchor().asLong()));
            next(9,"Second client hits the original labelled chest face");
        } else if(stage==9 && Files.exists(Path.of("layout-sent")) && inside.getBlockState(anchor().south()).is(Content.CRATE.get())) {
            require(builder.getMainHandItem().getCount()==1 && builder.containerMenu==builder.inventoryMenu,"Network placement opened a chest or spent the wrong amount");
            mark(inside,anchor().south());
            Files.writeString(Path.of("layout-unshift"),"release crouch");
            PocketService.recover(builder);PocketService.recover(visitor);PocketService.recover(owner);
            passed("A separately connected Survival client held crouch, aimed at the south face of the occupied labelled chest and placed its own labelled chest with a normal use packet.");
            next(11,"Open the linked tablet outdoors");
        } else if(stage==11 && !Actors.inside(owner) && PocketService.lease(owner.getUUID())==null) {
            nearDoor(owner);ensureTablet(owner);
            if(baseline)remote(owner);else request("open");
            next(12,"Inspect automatic tablet discovery");
        } else if(stage==12 && menu(owner)!=null && ready(inside,room)) {
            checkPhysical(inside);
            require(menu(owner).capacity()==expectedCapacity && menu(owner).occupiedSlots()==markers.size(),"Tablet capacity / occupied count");
            if(baseline)filter(menu(owner));else request("find");
            next(13,"Read every distinct stored item from the actual tablet slots");
        } else if(stage==13 && menu(owner)!=null && menu(owner).filteredView()) {
            checkDisplay(menu(owner));
            if(!baseline) {
                // Client-side inspection of the same synchronized marks is also required.
                var lines=markerLines();
                if(!Files.exists(Path.of("layout-expect")))Files.write(Path.of("layout-expect"),lines);
                if(!Files.exists(Path.of("layout-seen")))return;
                request("done");Files.write(Path.of("layout-saved"),lines);
                Files.writeString(Path.of("layout-id"),roomId.toString());Files.writeString(Path.of("layout-slots"),Integer.toString(expectedCapacity));
            }
            passed("The ordinary tablet opened outdoors without pairing any chest. All "+markers.size()+" component-named stacks appeared exactly once in "+expectedCapacity+" real storage slots.");
            owner.closeContainer();server.getPlayerList().saveAll();finish();
        }
    }

    private static BlockPos anchor(){return pos(9,11);}
    private static void heldCrates(ServerPlayer player){player.getInventory().selected=0;Actors.held(player,new ItemStack(Content.CRATE.get(),2));}
    private static void mark(ServerLevel level,BlockPos target) {
        int id=markers.size()+1;
        require(markers.put(target,id)==null,"Duplicate probe position "+target);
        var stack=new ItemStack(Items.AMETHYST_SHARD,id);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("Amethyst drawer "+id));
        var chest=container(level,target);require(chest.isEmpty(),"New chest was already filled at "+target);
        chest.setItem(0,stack);chest.setChanged();
        expectedCapacity+=chest.getContainerSize();
    }
    private static List<String> markerLines() {
        return markers.values().stream().map(id->"Amethyst drawer "+id+"="+id).sorted().toList();
    }
    private static void checkPhysical(ServerLevel level) {
        for(var item:markers.entrySet()){
            var stack=container(level,item.getKey()).getItem(0);var id=item.getValue();
            require(stack.is(Items.AMETHYST_SHARD) && stack.getCount()==id && stack.getHoverName().getString().equals("Amethyst drawer "+id),"Physical content changed at "+item.getKey());
        }
    }
    static List<String> shown(StoreMenu menu) {
        List<String> result=new ArrayList<>();
        for(int i=0;i<menu.shownSlots();i++){
            var item=menu.getSlot(i).getItem();
            if(item.is(Items.AMETHYST_SHARD))result.add(item.getHoverName().getString()+"="+item.getCount());
        }
        Collections.sort(result);return result;
    }
    private static void filter(StoreMenu menu) {
        menu.action(new Packets.Action(menu.containerId,menu.session(),menu.revision(),"text","amethyst",0));
    }
    private static void checkDisplay(StoreMenu menu) {
        require(markerLines().equals(shown(menu)),"Stored stack names/counts differ from tablet: expected "+markerLines()+" got "+shown(menu));
    }

    private static void aim(ServerPlayer player,BlockPos support,Direction face,float topYaw) {
        double cx=support.getX()+.5,cz=support.getZ()+.5;
        double x=cx+face.getStepX()*2.7,z=cz+face.getStepZ()*2.7;
        double y=support.getY()+(face==Direction.UP?2.2:0);
        float yaw=face==Direction.UP?topYaw:face.getOpposite().toYRot();
        float pitch=face==Direction.UP?90:(float)Math.toDegrees(Math.atan2(y+player.getEyeHeight()-(support.getY()+.4),2.7));
        player.connection.teleport(x,y,z,yaw,pitch);
        player.setYRot(yaw);player.setXRot(pitch);player.setYHeadRot(yaw);
    }
    private static void click(ServerPlayer player,BlockPos support,Direction face,float topYaw) {
        aim(player,support,face,topYaw);
        var hit=RoomEditor.looking(player);
        require(hit.getBlockPos().equals(support) && hit.getDirection()==face,"Aim missed "+face+" face of "+support+": "+hit);
        player.gameMode.useItemOn(player,player.serverLevel(),player.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
    }
    private static void labelledFace(ServerPlayer player,ServerLevel level,Direction face,boolean success) {
        var target=anchor().relative(face);
        heldCrates(player);player.setShiftKeyDown(true);click(player,anchor(),face,0);player.setShiftKeyDown(false);
        require(player.getMainHandItem().getCount()==(success?1:2),"Chest conservation failed on "+face);
        if(success) {
            require(level.getBlockState(target).is(Content.CRATE.get()) && player.containerMenu==player.inventoryMenu,"Labelled chest failed on "+face);
            mark(level,target);
        }else{
            require(level.isEmptyBlock(target) && menu(player)!=null && menu(player).capacity()==54,"Expected the pre-fix chest to intercept "+face);player.closeContainer();
        }
        checkPhysical(level);
    }
    private static BlockPos vanilla(ServerLevel level,int x,int z,Direction facing) {
        var pos=pos(x,z);owner.getInventory().selected=0;Actors.held(owner,new ItemStack(Items.CHEST));
        click(owner,pos.below(),Direction.UP,facing.getOpposite().toYRot());
        var state=level.getBlockState(pos);
        require(state.is(Blocks.CHEST) && state.getValue(ChestBlock.FACING)==facing && owner.getMainHandItem().isEmpty(),"Failed Survival floor placement/facing "+facing+" at "+pos);
        mark(level,pos);return pos;
    }
    private static void checkDoubles(ServerLevel level,List<BlockPos> positions,int halves){
        long joined=positions.stream().filter(p->level.getBlockState(p).getValue(ChestBlock.TYPE)!=ChestType.SINGLE).count();
        require(joined==halves,"Vanilla join count changed: expected "+halves+" halves, saw "+joined+" at "+positions);
        for(var p:positions){
            var s=level.getBlockState(p);
            if(s.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
                var partner=p.relative(ChestBlock.getConnectedDirection(s));var other=level.getBlockState(partner);
                require(positions.contains(partner)&&p.equals(partner.relative(ChestBlock.getConnectedDirection(other)))
                    &&s.getValue(ChestBlock.TYPE).getOpposite()==other.getValue(ChestBlock.TYPE),"Wrong double-chest partner at "+p);
            }
        }
    }
    private static void vanilla(ServerLevel level){
        checkDoubles(level,List.of(vanilla(level,0,0,Direction.NORTH),vanilla(level,1,0,Direction.NORTH),vanilla(level,2,0,Direction.NORTH)),2);
        checkDoubles(level,List.of(vanilla(level,31,31,Direction.SOUTH),vanilla(level,29,31,Direction.SOUTH),vanilla(level,30,31,Direction.SOUTH)),2);
        checkDoubles(level,List.of(vanilla(level,31,8,Direction.EAST),vanilla(level,31,9,Direction.EAST)),2);
        checkDoubles(level,List.of(vanilla(level,0,19,Direction.WEST),vanilla(level,0,18,Direction.NORTH)),0);
        checkDoubles(level,List.of(vanilla(level,16,0,Direction.NORTH),vanilla(level,15,0,Direction.NORTH)),2);
        Actors.held(owner,new ItemStack(Items.CHEST));Actors.useTop(owner,pos(16,0));
        require(owner.containerMenu instanceof net.minecraft.world.inventory.ChestMenu && owner.getMainHandItem().getCount()==1,"Non-sneaking use of ordinary chest switched to placement");
        owner.closeContainer();
        passed("Twelve physical vanilla chests placed with Survival targeting and exact consumption, across four walls, two corners and a chunk seam. All four facings, forward and gap-last sequences, third singles, differently facing neighbors, and ordinary double-chest opening matched vanilla.");
    }
    private static void restart(MinecraftServer server)throws Exception{
        if(stage==0){
            owner=Actors.join(server,"ChestOwner");nearDoor(owner);
            roomId=UUID.fromString(Files.readString(Path.of("layout-id")));room=PocketService.ledger(server).get(roomId);
            require(room!=null,"Room identity lost across restart");ensureTablet(owner);remote(owner);
            next(1,"Cold tablet use after restarting the changed room");
        }else if(stage==1 && menu(owner)!=null && ready(PocketService.dimension(server),room)){
            require(menu(owner).capacity()==Integer.parseInt(Files.readString(Path.of("layout-slots"))),"Storage capacity changed on restart");
            filter(menu(owner));require(shown(menu(owner)).equals(Files.readAllLines(Path.of("layout-saved"))),"Chest items changed across restart");
            passed("The same saved tablet opened the room from outdoors after a clean restart. Stored capacity, every distinct name and every stack count matched.");
            finish();
        }
    }
}
