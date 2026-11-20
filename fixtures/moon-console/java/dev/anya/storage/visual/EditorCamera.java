package dev.anya.storage.visual;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.ui.StorageGrid;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Hosted fixture only. The connected client uses the shipped item, menu, use and attack routes. */
final class EditorCamera {
    static final BlockPos HUB = new BlockPos(0,80,0), ENTRANCE = HUB.east(2);
    static final BlockPos DECOR = new BlockPos(11,65,20), PAD = new BlockPos(6,65,14);
    private static final BlockPos STARTER = new BlockPos(10,65,6), LANE = new BlockPos(16,65,21);
    private static final int TABLET=0, QUARTZ=1, VANILLA=2, CRATE=3, SHELF=4, DIAMONDS=5, EMPTY=6;
    private static final InteractionHand MAIN = InteractionHand.MAIN_HAND;
    private enum Kind { PLACE, MINE, STORE, TAKE }
    private record Trial(String name, Kind kind, int slot, BlockPos hit, BlockPos cell, Block expected,
                         int quartz, int crates, int shelves, int diamonds) {}
    private static Trial place(String name,int slot,BlockPos cell,Block expected,int q,int c,int s) {
        return new Trial(name,Kind.PLACE,slot,cell.below(),cell,expected,q,c,s,7);
    }
    private static Trial mine(String name,BlockPos hit,Block expected,int q,int c,int s,int d) {
        return new Trial(name,Kind.MINE,EMPTY,hit,hit,expected,q,c,s,d);
    }
    private static final List<Trial> trials = List.of(
        place("Off mode refuses a normal placement",QUARTZ,DECOR,Blocks.AIR,4,2,2),
        place("Quartz placed and paid",QUARTZ,DECOR,Blocks.QUARTZ_BLOCK,3,2,2),
        mine("Quartz removed and refunded",DECOR,Blocks.AIR,4,2,2,7),
        mine("The floor cannot be mined",DECOR.below(),null,4,2,2,7),
        place("Exit lane stays open",QUARTZ,LANE,Blocks.AIR,4,2,2),
        place("Vanilla chest refused without item loss",VANILLA,DECOR,Blocks.AIR,4,2,2),
        place("Display furnishing outside pads",SHELF,DECOR,Content.SHELF.get(),4,2,1),
        mine("Empty furnishing returned",DECOR,Blocks.AIR,4,2,2,7),
        place("Registered storage refused outside a pad",CRATE,DECOR,Blocks.AIR,4,2,2),
        place("Only a Labelled Storage Chest fits a pad",QUARTZ,PAD,Blocks.AIR,4,2,2),
        place("Optional Labelled Storage Chest placed",CRATE,PAD,Content.CRATE.get(),4,1,2),
        new Trial("Physical optional chest accepts seven diamonds",Kind.STORE,EMPTY,PAD,PAD,Content.CRATE.get(),4,1,2,0),
        mine("Occupied optional chest and its contents survive left-click",PAD,Content.CRATE.get(),4,1,2,0),
        new Trial("All seven diamonds withdrawn from the physical chest",Kind.TAKE,EMPTY,PAD,PAD,Content.CRATE.get(),4,1,2,7),
        mine("Empty optional chest removed and refunded",PAD,Blocks.AIR,4,2,2,7),
        mine("Original chest stays registered",STARTER,Content.CRATE.get(),4,2,2,7),
        place("Disabling via the wrench prevents another normal edit",QUARTZ,DECOR,Blocks.AIR,4,2,2)
    );
    private enum Step { SETUP, ENTER, ARRIVE, AIM, INPUT, CHEST, TRANSFER, VERIFY, PANEL_SELECT, PANEL_USE,
        PANEL_CLICK, PANEL_CLOSE, GUEST, REVOKED, EXIT, RETURNED, DONE }
    private static Step step=Step.SETUP;
    private static int ticks,index;
    private static UUID roomId;
    private static int originX, originZ;
    private static BlockState before;
    private static final List<String> notes=new ArrayList<>();
    private static CompletableFuture<Void> job;

    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException("Editor camera: "+message);}
    private static void next(Step value){step=value;ticks=0;System.out.println("Editor camera "+step+" #"+index);}
    private interface Work {void apply(ServerPlayer p)throws Exception;}
    private static void server(Minecraft mc,Work work) {
        var srv=mc.getSingleplayerServer();
        var id=mc.player.getUUID();
        job=srv.submit(()->{
            try { work.apply(srv.getPlayerList().getPlayer(id)); }
            catch(Exception e){throw new IllegalStateException("Editor server step "+index,e);}
        });
    }
    static BlockPos world(BlockPos local){return local.offset(originX,0,originZ);}
    static PocketLedger.Pocket room(ServerPlayer p)throws Exception {return PocketService.ledger(p.getServer()).get(roomId);}
    static void aim(ServerPlayer p,BlockPos hit,boolean floor) {
        int side=hit.getZ()-originZ>=27?-3:3;
        double x=hit.getX()+.5,z=hit.getZ()+.5+side;
        double standY=PocketService.inPocket(p.level())?65:80;
        var eye=new Vec3(x,standY+p.getEyeHeight(),z);
        var target=new Vec3(hit.getX()+.5,hit.getY()+(floor?.995:.45),hit.getZ()+.5);
        var delta=target.subtract(eye);
        float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance()));
        p.teleportTo(p.serverLevel(),x,standY,z,yaw,pitch);
    }
    private static void setup(ServerPlayer p) {
        var level=p.serverLevel();
        for(var pos:BlockPos.betweenClosed(HUB.offset(-5,-1,-5),HUB.offset(8,-1,8)))
            level.setBlockAndUpdate(pos,Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
        ((StorageEntity)level.getBlockEntity(HUB)).owner=p.getUUID();
        var entrance=PocketContent.ENTRANCE.get().defaultBlockState();
        level.setBlockAndUpdate(ENTRANCE,entrance);
        entrance.getBlock().setPlacedBy(level,ENTRANCE,entrance,p,new ItemStack(PocketContent.ITEM.get()));
        check(Config.setServerSetting(p,Config.get().revision,"expansion.pocketStorage","true"),"Enable pocket storage");
        check(Config.setServerSetting(p,Config.get().revision,"expansion.pocketEditing","true"),"Enable bounded editing");
        check(Config.setTabletOpeningChancePercent(p,Config.get().revision,0),"Use the immediate inventory path for edit controls");
        p.setGameMode(GameType.SURVIVAL);
        var inv=p.getInventory();inv.clearContent();inv.selected=TABLET;
        var tablet=new ItemStack(Content.CUPID_TABLET.get());inv.setItem(TABLET,tablet);
        inv.setItem(QUARTZ,new ItemStack(Items.QUARTZ_BLOCK,4));inv.setItem(VANILLA,new ItemStack(Items.CHEST,2));
        inv.setItem(CRATE,new ItemStack(Content.CRATE.get(),2));inv.setItem(SHELF,new ItemStack(Content.SHELF.get(),2));
        inv.setItem(DIAMONDS,new ItemStack(Items.DIAMOND,7));
        aim(p,ENTRANCE,false);
        p.setShiftKeyDown(true);
        try {
            for(var pos:List.of(HUB,ENTRANCE)){
                var hit=new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.SOUTH,.5),Direction.SOUTH,pos,false);
                p.gameMode.useItemOn(p,level,tablet,MAIN,hit);
            }
        } finally {p.setShiftKeyDown(false);}
        var be=(PocketContent.EntranceEntity)level.getBlockEntity(ENTRANCE);
        roomId=be.pocket;
        check(roomId!=null && roomId.equals(BagInventory.data(tablet).getUUID("PocketId")),"Bind original tablet through the hub and entrance");
        p.inventoryMenu.broadcastChanges();
    }
    private static BlockHitResult crosshair(Minecraft mc,BlockPos pos) {
        var hit=mc.player.pick(4.5,1,false);
        check(hit instanceof BlockHitResult b && hit.getType()==HitResult.Type.BLOCK && b.getBlockPos().equals(pos),
                "Crosshair expected "+pos+" but hit "+hit);
        return (BlockHitResult)hit;
    }
    private static void use(Minecraft mc,BlockPos pos){
        mc.gameMode.useItemOn(mc.player,MAIN,crosshair(mc,pos));
    }
    private static StorageGrid.Layout layout(Client.Screen screen)throws Exception {
        var field=Client.Screen.class.getDeclaredField("layout");field.setAccessible(true);
        return (StorageGrid.Layout)field.get(screen);
    }
    private static void click(Client.Screen screen,double x,double y)throws Exception {
        var layout=layout(screen);
        check(screen.mouseClicked(layout.left()+x*layout.scale(),layout.top()+y*layout.scale(),0),"Clickable screen target");
    }
    private static void verify(ServerPlayer p,Trial t)throws Exception{
        var level=p.serverLevel();
        var state=level.getBlockState(world(t.cell()));
        check(t.expected()==null ? state==before : state.is(t.expected()),t.name()+" world state "+state);
        var inv=p.getInventory();
        check(inv.countItem(Items.QUARTZ_BLOCK)==t.quartz() && inv.countItem(Items.CHEST)==2
                && inv.countItem(Content.CRATE.get().asItem())==t.crates()
                && inv.countItem(Content.SHELF.get().asItem())==t.shelves()
                && inv.countItem(Items.DIAMOND)==t.diamonds(),t.name()+" item conservation");
        var containers=PocketRoomLayout.present(level,room(p));
        check(containers.size()==4+2-t.crates(),t.name()+" registered chest count");
        int diamonds=containers.stream().mapToInt(c->c.countItem(Items.DIAMOND)).sum();
        check(diamonds==7-t.diamonds(),t.name()+" registered chest contents");
        check(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(originX,64,originZ,originX+32,76,originZ+32)).isEmpty(),
                t.name()+" unexpected world item drops");
        notes.add(t.name());
    }
    static void tick(Minecraft mc)throws Exception{
        if(job!=null){if(!job.isDone())return;job.join();job=null;}
        if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
        check(++ticks<550,"Timeout "+step+" at "+index);
        switch(step){
            case SETUP->{if(mc.screen!=null)return;server(mc,EditorCamera::setup);next(Step.ENTER);}
            case ENTER->{if(ticks<15||!mc.player.getMainHandItem().is(Content.CUPID_TABLET.get()))return;
                use(mc,ENTRANCE);next(Step.ARRIVE);}
            case ARRIVE->{if(!PocketService.inPocket(mc.level)||mc.screen!=null)return;
                server(mc,p->{var r=room(p);originX=r.x();originZ=r.z();check(!RoomEditor.active(p),"Room begins outside edit mode");});
                next(Step.AIM);}
            case AIM->{
                var t=trials.get(index);mc.player.getInventory().selected=t.slot();
                server(mc,p->{before=p.serverLevel().getBlockState(world(t.cell()));aim(p,world(t.hit()),t.hit().getY()==64);});
                next(Step.INPUT);}
            case INPUT->{
                if(ticks<12)return;
                var t=trials.get(index);
                var hit=crosshair(mc,world(t.hit()));
                if(t.kind()==Kind.MINE)mc.gameMode.startDestroyBlock(hit.getBlockPos(),hit.getDirection());
                else mc.gameMode.useItemOn(mc.player,MAIN,hit);
                next(t.kind()==Kind.STORE||t.kind()==Kind.TAKE?Step.CHEST:Step.VERIFY);}
            case CHEST->{
                if(!(mc.screen instanceof Client.Screen s)||!(mc.player.containerMenu instanceof StoreMenu m)||m.capacity()!=54||ticks<8)return;
                check(!m.localRoomPanel() && !m.privateView(),"Separate permitted physical chest UI");
                var t=trials.get(index);
                var l=layout(s);var box=t.kind()==Kind.STORE?l.inventory(27+DIAMONDS):l.storage(0);
                click(s,box.x()+8,box.y()+8);
                next(Step.TRANSFER);}
            case TRANSFER->{
                boolean shouldHold=trials.get(index).kind()==Kind.TAKE;
                if(ticks<8 || (mc.player.getInventory().countItem(Items.DIAMOND)==7)!=shouldHold)return;
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);next(Step.VERIFY);}
            case VERIFY->{
                if(ticks<10||mc.screen!=null)return;
                var t=trials.get(index);
                if(t.kind()==Kind.MINE)mc.gameMode.stopDestroyBlock();
                if(index==6)Screenshot.grab(mc.gameDirectory,"room-editor-decoration.png",mc.getMainRenderTarget(),m->{});
                server(mc,p->verify(p,t));index++;
                if(index==1||index==trials.size()-1)next(Step.PANEL_SELECT);
                else next(index==trials.size()?Step.GUEST:Step.AIM);}
            case PANEL_SELECT->{mc.player.getInventory().selected=TABLET;mc.player.setXRot(-75);next(Step.PANEL_USE);}
            case PANEL_USE->{if(ticks<8)return;mc.gameMode.useItem(mc.player,MAIN);next(Step.PANEL_CLICK);}
            case PANEL_CLICK->{
                if(!(mc.screen instanceof Client.Screen s)||!(mc.player.containerMenu instanceof StoreMenu m)
                        ||!m.localRoomPanel()||!m.roomEditAllowed()||ticks<16)return;
                boolean enabling=index==1;
                check(m.roomEditActive()!=enabling,"Synced current mode in local panel");
                Screenshot.grab(mc.gameDirectory,enabling?"room-editor-off.png":"room-editor-on.png",mc.getMainRenderTarget(),msg->{});
                click(s,51,78);next(Step.PANEL_CLOSE);}
            case PANEL_CLOSE->{if(ticks<5||mc.screen!=null)return;
                boolean enabling=index==1;
                server(mc,p->{check(RoomEditor.active(p)==enabling,"Wrench changed the server mode");notes.add("Local tablet wrench "+(enabling?"enabled":"disabled")+" editing");});
                next(Step.AIM);}
            case GUEST->{server(mc,EditorGuest::join);next(Step.REVOKED);}
            case REVOKED->{if(ticks<42)return;
                server(mc,p->{EditorGuest.exercise(p);aim(p,PocketService.exitPosition(room(p)),false);});
                next(Step.EXIT);}
            case EXIT->{if(ticks<15)return;use(mc,world(new BlockPos(16,65,28)));next(Step.RETURNED);}
            case RETURNED->{if(ticks<10||PocketService.inPocket(mc.level)||mc.screen!=null)return;
                server(mc,p->{check(p.serverLevel()==p.getServer().overworld() && p.distanceToSqr(Vec3.atCenterOf(ENTRANCE))<36,
                        "Returned safely near the original entrance");
                    check(!RoomEditor.active(p)&&PocketService.lease(p.getUUID())==null&&PocketService.ledger(p.getServer()).returning(p.getUUID())==null,"Clean owner exit");
                    check(p.getInventory().countItem(Items.DIAMOND)==7&&p.getInventory().countItem(Content.CRATE.get().asItem())==2,"Returned goods");
                    EditorGuest.finish(p);
                });next(Step.DONE);}
            case DONE->{
                var file=net.neoforged.fml.ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
                check(Files.isRegularFile(file)&&file.toString().endsWith(".jar"),"Expected packaged mod");
                var hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                Files.writeString(mc.gameDirectory.toPath().resolve("room-editor-checks.txt"),"Playable JAR SHA-256 "+hash+"\n"+String.join("\n",notes)
                        +"\nSecond server player: Visit only denied, Build granted, storage rights and revocation checked. Owner clicked the lodestone and returned with original goods.\n");
                mc.stop();}
        }
    }
    private EditorCamera(){}
}
