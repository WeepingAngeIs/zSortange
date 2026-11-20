package dev.anya.storage.visual;

import dev.anya.storage.BagInventory;
import dev.anya.storage.Client;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.appearance.GuardianBlink;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Focused capture on a disposable hosted client. Use the normal item-to-menu path on every open. */
final class ExpressionCamera {
    private static final BlockPos HUB = new BlockPos(0,80,0);
    private static final List<Integer> visits = new ArrayList<>();
    private static final int[] CAPTURE_COLUMN={6,5,8,6,5,4,4,6};
    private static final Set<Integer> captures = new HashSet<>();
    private static final List<String> receipt = new ArrayList<>();
    private static final Field CLOCK;
    static {
        try {
            CLOCK = Client.Screen.class.getDeclaredField("tabletIntro");
            CLOCK.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private enum Step { SETUP, SELECT, USE, OPEN, CLOSE, END }
    private static Step step=Step.SETUP;
    private static CompletableFuture<Void> task;
    private static int ticks, attempt, active=-1;
    private static void next(Step s){step=s;ticks=0;}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Expression review: "+why);}
    private static int slot(){return attempt%2==0?1:2;}
    private static boolean isCreative(){return slot()==2;}
    private static void bind(ServerPlayer p, int slot, ItemStack stack) {
        var inv=p.getInventory();inv.selected=slot;inv.setItem(slot,stack);
        var hit=new BlockHitResult(Vec3.atCenterOf(HUB).relative(Direction.NORTH,.5),Direction.NORTH,HUB,false);
        p.setShiftKeyDown(true);
        try {p.gameMode.useItemOn(p,p.serverLevel(),stack,InteractionHand.MAIN_HAND,hit);}
        finally {p.setShiftKeyDown(false);}
        require(BagInventory.data(stack).contains("HubPos"),"In-person hub bind");
    }
    private static void setup(ServerPlayer p){
        var l=p.serverLevel();
        for(var pos:BlockPos.betweenClosed(HUB.offset(-4,-1,-5),HUB.offset(4,-1,4)))
            l.setBlockAndUpdate(pos,Blocks.SMOOTH_STONE.defaultBlockState());
        l.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
        l.setBlockAndUpdate(HUB.east(2),Content.CRATE.get().defaultBlockState());
        var hub=(StorageEntity)l.getBlockEntity(HUB);
        var crate=(StorageEntity)l.getBlockEntity(HUB.east(2));
        hub.owner=crate.owner=p.getUUID();crate.setItem(0,new ItemStack(Items.DIAMOND,3));
        p.teleportTo(l,.5,80,-3.25,0,9);
        require(Config.setTabletOpeningChancePercent(p,Config.get().revision,100),"Enable eligible openings");
        bind(p,1,new ItemStack(Content.CUPID_TABLET.get()));
        bind(p,2,new ItemStack(Content.CREATIVE_TABLET.get()));
        p.getInventory().selected=0;p.inventoryMenu.broadcastChanges();
    }
    private static void server(Minecraft mc,java.util.function.Consumer<ServerPlayer> op){
        var srv=mc.getSingleplayerServer();var id=mc.player.getUUID();
        task=srv.submit(()->op.accept(srv.getPlayerList().getPlayer(id)));
    }
    static void frame(Minecraft mc) throws ReflectiveOperationException {
        if(step!=Step.OPEN || !(mc.screen instanceof Client.Screen s) || !(mc.player.containerMenu instanceof StoreMenu m)
                || !m.storageView() || !m.showsTabletIntro())return;
        var clock=(GuardianBlink)CLOCK.get(s);
        int col=clock.column(), row=clock.sequence();
        if(col<0)return;
        require(attempt<8 && row>=0 && row<8,"Eligible opening has one recognized choice");
        if(active<0)active=row;
        require(row==active,"Expression changed on one opening");
        if(col>=CAPTURE_COLUMN[row] && captures.add(row)){
            String name="expression-"+row+(isCreative()?"-creative":"-regular");
            Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),message->{});
        }
    }
    static void tick(Minecraft mc) throws Exception {
        if(task!=null){if(!task.isDone())return;task.join();task=null;}
        if(mc.player==null||mc.level==null||mc.getOverlay()!=null)return;
        require(++ticks<360,"Timed out at "+step+", opening "+attempt+", sequence "+active);
        switch(step){
            case SETUP -> {if(mc.screen!=null)return;server(mc,ExpressionCamera::setup);next(Step.SELECT);}
            case SELECT -> {
                if(mc.screen!=null || !mc.player.getInventory().getItem(2).is(Content.CREATIVE_TABLET.get()))return;
                mc.player.getInventory().selected=slot();next(Step.USE);
            }
            case USE -> {
                if(ticks<16)return;
                active=-1;
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);next(Step.OPEN);
            }
            case OPEN -> {
                if(!(mc.screen instanceof Client.Screen s) || !(mc.player.containerMenu instanceof StoreMenu m) || m.capacity()==0)return;
                require(m.capacity()==54 && m.creativeTabletOpening()==isCreative(),"Correct permitted tablet inventory");
                var clock=(GuardianBlink)CLOCK.get(s);
                if(attempt<8){
                    require(m.showsTabletIntro(),"100% opening must have an intro");
                    if(active<0 || clock.coversInput() || ticks<25)return;
                    require(captures.contains(active) && !visits.contains(active),"One complete shuffle before repeats, observed "+active+" after "+visits);
                    visits.add(active);
                    receipt.add((isCreative()?"Creative":"Regular")+" Tablet actual opening "+(attempt+1)+" sequence "+active);
                }else{
                    require(!m.showsTabletIntro() && !clock.coversInput() && clock.sequence()<0,
                            "Ordinary miss exposes inventory without selecting a sequence or covering input");
                    receipt.add("0% ordinary opening immediately exposed 54 slots without a portrait selection.");
                }
                s.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);next(Step.CLOSE);
            }
            case CLOSE -> {
                if(mc.screen!=null||ticks<8)return;
                attempt++;
                if(attempt==8)server(mc,p->require(Config.setTabletOpeningChancePercent(p,Config.get().revision,0),"Set miss policy"));
                next(attempt<=8?Step.SELECT:Step.END);
            }
            case END -> {
                require(visits.size()==8 && captures.size()==8,"Eight real openings captured");
                var mod=net.neoforged.fml.ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
                require(Files.isRegularFile(mod) && mod.toString().endsWith(".jar"),"Expected packaged mod");
                var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(mod)));
                Files.writeString(mc.gameDirectory.toPath().resolve("expression-review.txt"),"JAR SHA-256 "+hash+"\n"+String.join("\n",receipt)+"\n");mc.stop();
            }
        }
    }
    private ExpressionCamera(){}
}
