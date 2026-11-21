package dev.anya.storage.visual;

import dev.anya.storage.Client;
import dev.anya.storage.Content;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.ui.StorageGrid;
import dev.anya.storage.ui.StorageGrid.Box;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import org.lwjgl.glfw.GLFW;

/** The integrated client, server and optional EMI run from packaged JARs in an empty hosted world. */
@EventBusSubscriber(modid="anyamoonreview", value=Dist.CLIENT)
public final class ChestCamera {
    private static final BlockPos CRATE=new BlockPos(0,80,0), CUBE=CRATE.east(2);
    private enum Step { JOIN, USE, CRATE, KEY_SEARCH, KEY_OPEN, KEY_CLOSE, KEY_REOPEN, HOVER, TAKE, DEPOSIT, DRAWER, FAVORITES_ON, FILTER, FAVORITES_OFF, UNFILTER, DRAWER_CLOSED, DISPLAY, OFF,
        ON, EMPTY, RESIZE, COMPACT_DRAWER, COMPACT_HOVER, COMPACT_WHEEL, COMPACT, SCROLLED, CUBE_USE, CUBE_COMPACT, HOTBAR, DEPOSIT_HOTBAR, WIDE, WHEEL, BOTTOM, RETURN_TOP,
        EMI_SEARCH, EMI_CELL, EMI_HOVER, EMI_RECIPE, RESUMED, POST_CLICK, FINISH, RECEIPT, DONE }
    private static Step step=Step.JOIN;
    private static final int HOTBAR_SLOT=4, HOTBAR_ORDINAL=27+HOTBAR_SLOT;
    private static int ticks, filledTips, emptyTips, favoritesTips, cubeId;
    private static CompletableFuture<Void> job;
    private static Box sidebar;
    private static final List<String> notes=new ArrayList<>(), shots=new ArrayList<>();

    static boolean withEmi(){return "1".equals(System.getenv("ANYA_CHEST_EMI"));}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Chest review "+step+": "+why);}
    private static void next(Step s){step=s;ticks=0;favoritesTips=0;System.out.println("Chest review "+s);}
    private static void server(Minecraft mc,Consumer<ServerPlayer> action){
        var srv=mc.getSingleplayerServer();var id=mc.player.getUUID();
        job=srv.submit(()->action.accept(srv.getPlayerList().getPlayer(id)));
    }
    private static StorageEntity block(ServerPlayer p,BlockPos pos){return (StorageEntity)p.serverLevel().getBlockEntity(pos);}
    private static void setup(ServerPlayer p){
        var level=p.serverLevel();
        for(var pos:BlockPos.betweenClosed(CRATE.offset(-4,-1,-4),CUBE.offset(4,-1,5)))
            level.setBlockAndUpdate(pos,Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(CRATE,Content.CRATE.get().defaultBlockState());
        level.setBlockAndUpdate(CUBE,Content.CUBE.get().defaultBlockState());
        var crate=block(p,CRATE);var cube=block(p,CUBE);crate.owner=p.getUUID();cube.owner=p.getUUID();
        crate.setItem(0,new ItemStack(Items.APPLE,2));crate.setItem(49,new ItemStack(Items.IRON_INGOT,7));
        cube.setItem(60,new ItemStack(Items.DIAMOND,3));cube.setItem(215,new ItemStack(Items.GOLD_INGOT,5));
        p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getInventory().selected=8;
        p.getInventory().setItem(9,new ItemStack(Items.EMERALD,3));
        p.getInventory().setItem(HOTBAR_SLOT,new ItemStack(Items.LAPIS_LAZULI,4));p.inventoryMenu.broadcastChanges();
        p.teleportTo(level,1.5,80,3.25,180,12);
        ChestProjectionPrivacy.verify(p);
    }
    private static void use(Minecraft mc,BlockPos pos){
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).relative(Direction.SOUTH,.5),Direction.SOUTH,pos,false));
    }
    private static void shot(Minecraft mc,String name){
        require(!shots.contains(name),"duplicate screenshot");shots.add(name);
        Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});
    }
    private static void point(Minecraft mc,Box b) {
        require(b.width()>0&&b.height()>0,"Control has no bounds: "+b);
        var w=mc.getWindow();long handle=w.getWindow();
        double x=(b.x()+b.width()*.5)*w.getScreenWidth()/w.getGuiScaledWidth();
        double y=(b.y()+b.height()*.5)*w.getScreenHeight()/w.getGuiScaledHeight();
        GLFW.glfwSetCursorPos(handle,x,y);
        var callback=GLFW.glfwSetCursorPosCallback(handle,null);
        GLFW.glfwSetCursorPosCallback(handle,callback);
        require(callback!=null,"No game cursor callback");callback.invoke(handle,x,y);
    }
    private static void click(Minecraft mc,Box b,int button){
        point(mc,b);long handle=mc.getWindow().getWindow();
        var callback=GLFW.glfwSetMouseButtonCallback(handle,null);GLFW.glfwSetMouseButtonCallback(handle,callback);
        require(callback!=null,"No game mouse callback");
        callback.invoke(handle,button,GLFW.GLFW_PRESS,0);callback.invoke(handle,button,GLFW.GLFW_RELEASE,0);
    }
    private static void wheel(Minecraft mc,Box b,int amount){
        point(mc,b);long handle=mc.getWindow().getWindow();
        var callback=GLFW.glfwSetScrollCallback(handle,null);GLFW.glfwSetScrollCallback(handle,callback);
        require(callback!=null,"No game scroll callback");callback.invoke(handle,0,amount);
    }
    private static void key(Minecraft mc,int key){
        long handle=mc.getWindow().getWindow();
        var callback=GLFW.glfwSetKeyCallback(handle,null);GLFW.glfwSetKeyCallback(handle,callback);
        require(callback!=null,"No game keyboard callback");
        int scan=GLFW.glfwGetKeyScancode(key);
        callback.invoke(handle,key,scan,GLFW.GLFW_PRESS,0);callback.invoke(handle,key,scan,GLFW.GLFW_RELEASE,0);
    }
    private static void resize(Minecraft mc,boolean wide){
        GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),wide?960:690,wide?640:500);
    }
    private static boolean inside(Box outer,Box inner){
        return inner.width()>0 && inner.height()>0 && inner.x()>=outer.x() && inner.y()>=outer.y()
                && inner.x()+inner.width()<=outer.x()+outer.width() && inner.y()+inner.height()<=outer.y()+outer.height();
    }
    private static boolean overlap(Box a,Box b){
        return a.x()<b.x()+b.width() && b.x()<a.x()+a.width() && a.y()<b.y()+b.height() && b.y()<a.y()+a.height();
    }
    private static void geometry(Client.Screen screen,StoreMenu menu){
        Box main=screen.emiMainBounds(),display=new Box(0,0,screen.width,screen.height);
        require(inside(display,main),"Main chest bounds "+main+" exceed "+display);
        Box footer=screen.chestFooterBounds();
        require(inside(main,footer),"Empty or off-panel help footer: "+footer+" / "+main);
        List<Box> cells=new ArrayList<>();
        for(int i=0;i<menu.shownSlots();i++)cells.add(screen.chestCellBounds(i));
        for(int i=0;i<36;i++){
            Box slot=screen.chestInventoryBounds(i);
            require(!overlap(footer,slot),"Help footer overlaps player slot "+i+": "+footer+" / "+slot);
            cells.add(slot);
        }
        cells.add(screen.chestSearchBounds());cells.add(screen.chestDrawerBounds());
        for(int i=0;i<cells.size();i++){
            require(inside(main,cells.get(i)),"Cell escapes main bounds: "+i);
            for(int j=0;j<i;j++)require(!overlap(cells.get(i),cells.get(j)),"Overlapping cells "+i+" and "+j);
        }
        for(var extra:screen.emiExtraBounds())require(inside(display,extra),"Offscreen exclusion "+extra);
        if(withEmi()){
            var search=EmiProbe.searchBounds();
            require(inside(display,search),"EMI search field outside the actual window: "+search);
            require(!overlap(main,search),"EMI search field overlaps the chest panel or footer: "+main+" / "+search);
            require(!overlap(footer,search),"Help footer overlaps the EMI search field: "+footer+" / "+search);
            for(int ordinal=0;ordinal<36;ordinal++){
                var slot=screen.chestInventoryBounds(ordinal);
                require(!overlap(slot,search) && !search.contains(slot.x()+slot.width()*.5,slot.y()+slot.height()*.5),
                        "Player inventory cell "+ordinal+" overlaps EMI search: "+slot+" / "+search);
            }
            notes.add("EMI search "+search+" clear of the main panel and all 36 player slot rectangles.");
        }
        notes.add((menu.cubeView()?"Cube":"Chest")+" at "+screen.width+"x"+screen.height+": "+menu.visibleColumns()+" columns x "+menu.visibleRows()+" rows, "+menu.shownSlots()+" visible slots. Help footer "+footer+" clears all 36 player slots.");
    }
    @SubscribeEvent public static void tooltip(RenderTooltipEvent.Pre e){
        if(!"1".equals(System.getenv("ANYA_CHEST_REVIEW")))return;
        if(step==Step.HOVER&&e.getItemStack().is(Items.IRON_INGOT))filledTips++;
        if(step==Step.EMPTY&&e.getItemStack().isEmpty()&&!e.getComponents().isEmpty())emptyTips++;
    }
    @SubscribeEvent public static void favoritesHover(RenderTooltipEvent.GatherComponents e){
        if((step!=Step.COMPACT_HOVER && step!=Step.FAVORITES_ON && step!=Step.FAVORITES_OFF)
                || !"1".equals(System.getenv("ANYA_CHEST_REVIEW")))return;
        var mc=Minecraft.getInstance();
        if(mc.screen instanceof Client.Screen && mc.player.containerMenu instanceof StoreMenu menu && !e.getTooltipElements().isEmpty()){
            String title=menu.getSlot(52).getItem().getHoverName().getString();
            if(!title.isBlank() && e.getTooltipElements().getFirst().left().map(t->t.getString().equals(title)).orElse(false))favoritesTips++;
        }
    }
    private static String favoritesState(StoreMenu menu){
        return "menu="+menu.containerId+" session="+menu.session()+" revision="+menu.revision()
                +" filtered="+menu.filteredView()+" results="+menu.resultCount()+" button="+menu.getSlot(52).getItem().getHoverName().getString();
    }
    @SubscribeEvent public static void afterFrame(RenderFrameEvent.Post e){
        if((step!=Step.FAVORITES_ON && step!=Step.FAVORITES_OFF) || favoritesTips==0)return;
        var mc=Minecraft.getInstance();
        if(!(mc.screen instanceof Client.Screen screen) || !(mc.player.containerMenu instanceof StoreMenu menu))return;
        require(screen.chestDrawerOpen(),"Drawer closed before its hovered button was clicked");
        if(withEmi())require(!EmiProbe.hoveringSidebarItem()&&!EmiProbe.searchFocused(),"EMI intercepted Favorites hover");
        Step after=step==Step.FAVORITES_ON?Step.FILTER:Step.UNFILTER;
        var box=screen.chestControlBounds(52);
        System.out.println("Chest review clicking rendered Favorites "+box+" "+favoritesState(menu));
        click(mc,box,0);next(after);
    }
    private static void missingFavoriteAck(Minecraft mc,StoreMenu menu){
        if(ticks!=80)return;
        var client=favoritesState(menu);
        server(mc,p->{String server=p.containerMenu instanceof StoreMenu actual?favoritesState(actual):p.containerMenu.getClass().getName();
            throw new IllegalStateException("Chest review Favorites click received no matching update. Client "+client+" / server "+server);});
    }
    static void tick(Minecraft mc)throws Exception{
        if(step==Step.DONE)return;
        if(job!=null){if(!job.isDone())return;job.join();job=null;}
        require(++ticks<2400,"Timed out. Screen="+(mc.screen==null?"none":mc.screen.getClass().getName()));
        if(ticks<4)return; // Let an actual client frame paint each newly synchronized revision.
        var screen=mc.screen instanceof Client.Screen value?value:null;
        var menu=mc.player!=null && mc.player.containerMenu instanceof StoreMenu value?value:null;
        switch(step){
            case JOIN->{if(mc.player==null||mc.level==null||mc.screen!=null||mc.getOverlay()!=null)return;
                require(ModList.get().isLoaded("emi")==withEmi(),"Wrong recipe browser classpath");
                server(mc,ChestCamera::setup);next(Step.USE);}
            case USE->{if(mc.player.getY()<79 || mc.player.getInventory().getItem(9).getCount()!=3)return;
                mc.player.getInventory().selected=8;use(mc,CRATE);next(Step.CRATE);}
            case CRATE->{if(screen==null||!menu.chestView()||menu.shownSlots()!=54 || !menu.getSlot(StorageGrid.chestSlot(49)).getItem().is(Items.IRON_INGOT))return;
                require(!menu.cubeView()&&menu.visibleColumns()==9&&!screen.chestDrawerOpen(),"Initially closed 54-slot chest");
                geometry(screen,menu);shot(mc,"chest-wide");
                require(mc.player.getMainHandItem().isEmpty() && menu.getCarried().isEmpty(),"Empty hand for hover");
                click(mc,screen.chestSearchBounds(),0);next(Step.KEY_SEARCH);}
            case KEY_SEARCH->{require(screen!=null && screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox input && input.isFocused(),"Chest search did not take keyboard focus");
                key(mc,GLFW.GLFW_KEY_TAB);key(mc,GLFW.GLFW_KEY_ENTER);next(Step.KEY_OPEN);}
            case KEY_OPEN->{require(screen!=null && screen.chestDrawerOpen(),"Tab from search and Enter did not open Settings");
                key(mc,GLFW.GLFW_KEY_ESCAPE);next(Step.KEY_CLOSE);}
            case KEY_CLOSE->{require(screen!=null && !screen.chestDrawerOpen(),"Escape did not close just the Settings drawer");
                key(mc,GLFW.GLFW_KEY_ENTER);next(Step.KEY_REOPEN);}
            case KEY_REOPEN->{require(screen!=null && screen.chestDrawerOpen(),"Settings did not retain keyboard focus after Escape");
                click(mc,screen.chestDrawerBounds(),0);
                notes.add("Native Tab and Enter opened Settings from chest search. Escape closed the drawer and Enter reopened it from restored focus.");
                point(mc,screen.chestCellBounds(49));next(Step.HOVER);}
            case HOVER->{if(filledTips==0)return;shot(mc,"chest-item-tooltip");click(mc,screen.chestCellBounds(49),1);next(Step.TAKE);}
            case TAKE->{if(menu.getSlot(StorageGrid.chestSlot(49)).getItem().getCount()!=6 || mc.player.getInventory().countItem(Items.IRON_INGOT)!=1)return;
                require(menu.getSlot(54).getItem().is(Items.EMERALD),"Expected first inventory row");
                click(mc,screen.chestInventoryBounds(0),0);next(Step.DEPOSIT);}
            case DEPOSIT->{if(mc.player.getInventory().countItem(Items.EMERALD)!=0 || menu.occupiedSlots()!=3)return;
                server(mc,p->{var c=block(p,CRATE);require(c.getItem(49).getCount()==6&&c.countItem(Items.EMERALD)==3
                        &&p.getInventory().countItem(Items.IRON_INGOT)==1,"Physical chest and player conservation");});
                click(mc,screen.chestDrawerBounds(),0);next(Step.DRAWER);}
            case DRAWER->{require(screen.chestDrawerOpen() && screen.chestControlBounds(52).width()>0,"Settings drawer did not open");
                var drawer=screen.chestSettingsPanelBounds();
                require(!overlap(screen.emiMainBounds(),drawer)&&screen.emiExtraBounds().stream().anyMatch(b->inside(b,drawer)),
                        "Wide settings drawer must have a separate exclusion outside the main panel");
                if(withEmi())EmiProbe.clearOfSidebar(screen.chestSettingsPanelBounds());
                shot(mc,"chest-settings");point(mc,screen.chestControlBounds(52));next(Step.FAVORITES_ON);}
            case FAVORITES_ON,FAVORITES_OFF->{if(ticks==80)throw new IllegalStateException("Chest review did not render the Favorites hover: "+favoritesState(menu));}
            case FILTER->{if(!menu.filteredView()||menu.resultCount()!=0){missingFavoriteAck(mc,menu);return;}
                point(mc,screen.chestControlBounds(52));next(Step.FAVORITES_OFF);}
            case UNFILTER->{if(menu.filteredView()||menu.resultCount()!=54){missingFavoriteAck(mc,menu);return;}
                click(mc,screen.chestDrawerBounds(),0);next(Step.DRAWER_CLOSED);}
            case DRAWER_CLOSED->{require(!screen.chestDrawerOpen(),"Settings did not close");
                click(mc,screen.chestDisplayButtonBounds(),0);next(Step.DISPLAY);}
            case DISPLAY->{require(menu.tooltipsEnabled()&&screen.chestControlBounds(200).width()>0,"Missing tooltip control");
                click(mc,screen.chestControlBounds(200),0);next(Step.OFF);}
            case OFF->{if(menu.tooltipsEnabled())return;click(mc,screen.chestControlBounds(200),0);next(Step.ON);}
            case ON->{if(!menu.tooltipsEnabled())return;click(mc,screen.chestDisplayButtonBounds(),0);
                require(menu.getSlot(5).getItem().isEmpty(),"Empty hover target");
                point(mc,screen.chestCellBounds(5));next(Step.EMPTY);}
            case EMPTY->{if(emptyTips==0)return;click(mc,screen.chestDrawerBounds(),0);next(Step.RESIZE);}
            case RESIZE->{require(screen.chestDrawerOpen(),"Settings must be open before resizing");
                resize(mc,false);next(Step.COMPACT_DRAWER);}
            case COMPACT_DRAWER->{if(screen.width>=370 || menu.shownSlots()>=54)return;
                require(screen.chestDrawerOpen() && inside(screen.emiMainBounds(),screen.chestSettingsPanelBounds()),"Compact settings drawer escaped main bounds after resize");
                if(withEmi())EmiProbe.clearOfSidebar(screen.chestSettingsPanelBounds());
                point(mc,screen.chestControlBounds(52));next(Step.COMPACT_HOVER);}
            case COMPACT_HOVER->{if(favoritesTips==0)return;
                if(withEmi())require(!EmiProbe.hoveringSidebarItem(),"EMI sidebar hover under the drawer action");
                shot(mc,"chest-compact-settings");wheel(mc,screen.chestControlBounds(52),-1);next(Step.COMPACT_WHEEL);}
            case COMPACT_WHEEL->{if(ticks<12)return;
                require(menu.firstSlot()==0,"Wheel over the drawer scrolled the underlying chest");
                click(mc,screen.chestDrawerBounds(),0);next(Step.COMPACT);}
            case COMPACT->{if(screen.width>=370 || menu.shownSlots()>=54)return;
                require(!screen.chestDrawerOpen(),"Closed compact settings still covered cells");
                geometry(screen,menu);shot(mc,"chest-compact");wheel(mc,screen.chestCellBounds(2),-1);next(Step.SCROLLED);}
            case SCROLLED->{if(menu.firstSlot()!=9)return;
                notes.add("Real mouse input withdrew one from chest slot 50, deposited emeralds from player slot 10, scrolled one row, changed Favorites and toggled tooltips. Occupied and empty slot hover events observed.");
                screen.onClose();next(Step.CUBE_USE);}
            case CUBE_USE->{if(mc.screen!=null)return;use(mc,CUBE);next(Step.CUBE_COMPACT);}
            case CUBE_COMPACT->{if(screen==null||!menu.cubeView() || menu.visibleColumns()!=9 || menu.capacity()!=216)return;
                geometry(screen,menu);resize(mc,true);next(Step.HOTBAR);}
            case HOTBAR->{if(screen.width!=480 || screen.height!=320 || menu.visibleColumns()!=12 || menu.shownSlots()<=60)return;
                geometry(screen,menu);
                require(menu.getSlot(54+HOTBAR_ORDINAL).getItem().is(Items.LAPIS_LAZULI),"Missing middle hotbar transfer stack");
                click(mc,screen.chestInventoryBounds(HOTBAR_ORDINAL),0);next(Step.DEPOSIT_HOTBAR);}
            case DEPOSIT_HOTBAR->{if(mc.player.getInventory().countItem(Items.LAPIS_LAZULI)!=0)return;
                if(withEmi())require(!EmiProbe.searchFocused(),"Middle hotbar click focused EMI search");
                server(mc,p->{var cube=block(p,CUBE);
                    require(cube.countItem(Items.LAPIS_LAZULI)==4 && p.getInventory().getItem(HOTBAR_SLOT).isEmpty(),
                            "Middle hotbar deposit did not reach the physical cube");});
                shot(mc,"cube-hotbar-clear");
                notes.add("At 480x320 GUI size, clicking the bottom middle hotbar slot deposited four lapis into the physical cube.");
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),960,720);next(Step.WIDE);}
            case WIDE->{if(screen.width!=480 || screen.height!=360 || menu.visibleColumns()!=12 || menu.shownSlots()!=108)return;
                require(menu.firstSlot()==0&&menu.getSlot(StorageGrid.chestSlot(60)).getItem().is(Items.DIAMOND),"Larger cube cell did not match physical slot 61");
                cubeId=menu.containerId;geometry(screen,menu);shot(mc,"cube-wide");wheel(mc,screen.chestCellBounds(2),-1);next(Step.WHEEL);}
            case WHEEL->{if(menu.firstSlot()!=12)return;
                // Each tick group sends one native wheel notch, bounded by the server's current acknowledged row.
                next(Step.BOTTOM);}
            case BOTTOM->{if(menu.firstSlot()+menu.shownSlots()<216){
                    if(ticks%10==0)wheel(mc,screen.chestCellBounds(2),-1);return;}
                int last=215-menu.firstSlot();require(menu.getSlot(StorageGrid.chestSlot(last)).getItem().is(Items.GOLD_INGOT),"Last cube slot");
                click(mc,screen.chestCellBounds(last),1);next(Step.RETURN_TOP);}
            case RETURN_TOP->{if(mc.player.getInventory().countItem(Items.GOLD_INGOT)!=1)return;
                if(menu.firstSlot()>0){if(ticks%10==0)wheel(mc,screen.chestCellBounds(1),1);return;}
                if(withEmi())next(Step.EMI_SEARCH);else next(Step.RESUMED);}
            case EMI_SEARCH->{if(!EmiProbe.ready())return;EmiProbe.search();next(Step.EMI_CELL);}
            case EMI_CELL->{if(ticks%10!=0)return;sidebar=EmiProbe.findSidebar(screen);if(sidebar==null)return;
                EmiProbe.clearOfSidebar(screen.emiMainBounds());point(mc,sidebar);next(Step.EMI_HOVER);}
            case EMI_HOVER->{if(!EmiProbe.hovered())return;shot(mc,"emi-sidebar");click(mc,sidebar,0);next(Step.EMI_RECIPE);}
            case EMI_RECIPE->{if(mc.screen==null||!mc.screen.getClass().getName().equals("dev.emi.emi.screen.RecipeScreen"))return;
                shot(mc,"emi-recipe");mc.screen.onClose();next(Step.RESUMED);}
            case RESUMED->{if(screen==null||menu==null||menu.containerId!=cubeId || !menu.cubeView()||menu.visibleColumns()!=12
                        ||menu.firstSlot()!=0||!menu.getSlot(StorageGrid.chestSlot(60)).getItem().is(Items.DIAMOND))return;
                click(mc,screen.chestCellBounds(60),0);next(Step.POST_CLICK);}
            case POST_CLICK->{if(mc.player.getInventory().countItem(Items.DIAMOND)!=3)return;
                server(mc,p->{var c=block(p,CUBE);require(c.getItem(215).getCount()==4&&c.getItem(60).isEmpty()
                        &&p.getInventory().countItem(Items.DIAMOND)==3&&p.getInventory().countItem(Items.GOLD_INGOT)==1,"Physical cube conservation");
                    require(p.getMainHandItem().isEmpty()&&p.containerMenu.getCarried().isEmpty(),"Unexpected carried item");});
                screen.onClose();next(Step.FINISH);}
            case FINISH->{if(mc.screen!=null || mc.player.containerMenu!=mc.player.inventoryMenu)return;
                notes.add(withEmi()?"EMI sidebar resolved a crafting table, opened the live EMI RecipeScreen, and returned to the same cube. The next chest click withdrew exactly three diamonds."
                        :"EMI absent. Both chest sizes opened and all transfer, drawer, hover and viewport checks ran.");
                next(Step.RECEIPT);}
            case RECEIPT->{Path dir=mc.gameDirectory.toPath();
                for(String shot:shots)if(!Files.isRegularFile(dir.resolve("screenshots/"+shot+".png")))return;
                var file=ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
                require(Files.isRegularFile(file),"Expected packaged mod");var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                if(withEmi()){
                    var emi=ModList.get().getModContainerById("emi").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
                    var actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(emi)));
                    require(actual.equals("b68691f94f0727fc517cac2ab55bd6658d1179cc8cc715fe610b60c37469bc4c"),"Wrong full EMI JAR: "+actual);
                    notes.add("EMI 1.1.24+1.21.1 SHA-256 "+actual);
                }
                Files.writeString(dir.resolve("CHEST-CHECKS.txt"),"Playable JAR SHA-256 "+hash+"\nEMI present: "+withEmi()+"\n"+String.join("\n",notes)+"\nScreenshots: "+String.join(", ",shots)+"\n");
                next(Step.DONE);mc.stop();}
            case DONE->{}
        }
    }
    private ChestCamera(){}
}
