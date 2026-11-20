package dev.anya.storage;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.anya.storage.ui.ControlGlyph;
import dev.anya.storage.ui.ControlSprites;
import dev.anya.storage.ui.StorageGrid;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.resources.ResourceLocation;
import dev.anya.storage.appearance.*;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.List;

@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class Client {
    private static final KeyMapping QUICK=new KeyMapping("key.anyastorage.quick",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping DUMP=new KeyMapping("key.anyastorage.dump",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping UNDO=new KeyMapping("key.anyastorage.undo_dump",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping BAG=new KeyMapping("key.anyastorage.bag",net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,org.lwjgl.glfw.GLFW.GLFW_KEY_B,"key.categories.anyastorage");
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) { event.register(Content.MENU.get(),Screen::new); }
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(QUICK);event.register(DUMP);event.register(UNDO);event.register(BAG); }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) { event.registerBlockEntityRenderer(Content.STORAGE.get(),ChestRenderer::new); }
    @SubscribeEvent public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event){event.enqueueWork(()->{if(BackpackAccess.accessories())dev.anya.storage.compat.AccessoriesClient.register();});}
    public static KeyMapping bagConflict(){
        for(KeyMapping other:Minecraft.getInstance().options.keyMappings){
            if(other==BAG||other.isUnbound()||!other.getKey().equals(BAG.getKey())||other.getKeyModifier()!=BAG.getKeyModifier())continue;
            if(other.getKeyConflictContext().isActive()&&(BAG.getKeyConflictContext().conflicts(other.getKeyConflictContext())||other.getKeyConflictContext().conflicts(BAG.getKeyConflictContext())))return other;
        }
        return null;
    }
    @EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
    public static final class Tick {
        private static String warned="";
        @SubscribeEvent public static void tick(ClientTickEvent.Post event){
            boolean quick=false,dump=false,undo=false,bag=false;while(QUICK.consumeClick())quick=true;while(DUMP.consumeClick())dump=true;while(UNDO.consumeClick())undo=true;while(BAG.consumeClick())bag=true;
            var mc=Minecraft.getInstance();if(mc.player==null){warned="";return;}if(mc.screen!=null)return;
            KeyMapping conflict=bagConflict();String signature=conflict==null?"":BAG.getKey().getName()+"/"+conflict.getName();
            if(!signature.equals(warned)){warned=signature;if(conflict!=null)mc.player.displayClientMessage(Component.literal("Anya backpack key conflicts with ").append(Component.translatable(conflict.getName())).append(". Rebind Open Anya Backpack in Controls; no keys were changed."),false);}
            if(quick)sendKey("quick");if(dump)sendKey("dump");if(undo)sendKey("undo_dump");if(bag&&conflict==null)sendKey("bag");
        }
        private static void sendKey(String action){PacketDistributor.sendToServer(new Packets.Action(-1,0,0,action,"",0));}
    }
    /** Manual drawing and hit testing avoid vanilla cursor prediction over display-only slots. */
    public static final class Screen extends AbstractContainerScreen<StoreMenu> {
        private static WeakReference<Player> tabletViewer = new WeakReference<>(null);
        private static GuardianBlink.Playlist tabletVisits = new GuardianBlink.Playlist();
        private final GuardianBlink tabletIntro;
        private final TabletBootScreen tabletBoot = new TabletBootScreen();
        private final Player openingPlayer;
        private final boolean tabletPresentation;
        private boolean openingClassified, tabletCoversView;
        private boolean retired, closeRequested, cleaned;
        private static final int STORAGE_CAMEO_MS = 650;
        private long cameoStarted = Long.MIN_VALUE;
        private EditBox input;
        private int debounce,ticks,pendingRow=-1,pendingRows=5,lastViewport=-20;
        private String sent="";
        private boolean dragging;
        private int pressedSlot=-1;
        private long pressedUntil;
        private static final String[] PERMISSION_LABELS={"View","Deposit","Withdraw","Activity","Visit","Build"};
        private dev.anya.storage.ui.StorageGrid.Layout layout;
        public Screen(StoreMenu menu,Inventory inventory,Component title){
            super(menu,inventory,title);
            openingPlayer = inventory.player;
            tabletPresentation = TabletDevice.enabled();
            tabletIntro = new GuardianBlink(tabletPresentation);
        }
        private void retire() {
            retired = true;
            dragging = false;
            tabletCoversView = false;
            tabletIntro.dismiss();
        }
        private boolean activeScreen() {
            if (retired) return false;
            if (minecraft == null || minecraft.player == null || minecraft.player != openingPlayer
                    || minecraft.player.containerMenu != menu) {
                retire();
                return false;
            }
            // A temporary GUI layer can cover and later uncover the same screen and menu.
            return minecraft.screen == this;
        }
        private void classifyOpening() {
            // The server sends readiness, permissions and the opening decision in one word.
            // Until it arrives, never cover or hold back the ordinary inventory.
            if (openingClassified || !menu.storageView()) return;
            openingClassified = true;
            tabletCoversView = showTabletFrame() && menu.showsTabletIntro();
            if (tabletCoversView) {
                if (tabletViewer.get() != openingPlayer) {
                    tabletViewer = new WeakReference<>(openingPlayer);
                    tabletVisits = new GuardianBlink.Playlist();
                }
                tabletIntro.choose(tabletVisits);
            }
            if (!menu.privateView() && !menu.remoteOpening() && menu.showsStorageCameo())
                cameoStarted = net.minecraft.Util.getMillis();
        }
        private boolean showTabletFrame() {
            return tabletPresentation && menu.storageView() && !menu.privateView()
                    && menu.remoteOpening() && menu.hasTabletOpeningRule();
        }
        private boolean updateTabletCover() {
            classifyOpening();
            if (tabletCoversView && !showTabletFrame()) {
                tabletIntro.dismiss();
                tabletCoversView = false;
            }
            return tabletCoversView;
        }
        private boolean drawTabletIntro(GuiGraphics graphics) {
            if (!updateTabletCover()) return false;
            long now = net.minecraft.Util.getMillis();
            tabletIntro.present(now, true, true);
            tabletCoversView = tabletIntro.coversInput();
            if (tabletCoversView) tabletBoot.draw(graphics, width, height, tabletIntro, openingPlayer, now, menu.creativeTabletOpening());
            return tabletCoversView;
        }
        private boolean blockTabletInput() {
            return !activeScreen() || updateTabletCover();
        }
        private void drawStorageCameo(GuiGraphics graphics) {
            if (cameoStarted == Long.MIN_VALUE) return;
            if (!menu.storageView() || menu.privateView() || menu.remoteOpening() || !menu.showsStorageCameo()
                    || net.minecraft.Util.getMillis() - cameoStarted >= STORAGE_CAMEO_MS) {
                cameoStarted = Long.MIN_VALUE;
                return;
            }
            // This is the decorative card to the left of the grid, away from every inventory target.
            TabletDevice.drawCameo(graphics, 22, 79);
        }
        private void reflow(){
            layout=dev.anya.storage.ui.StorageGrid.layout(width,height,menu.resultCount(),menu.storageView());
            if (showTabletFrame()) layout = TabletDevice.fitMenu(layout, width, height);
        }
        @Override protected void init(){
            super.init();reflow();String previous=input==null?sent:input.getValue();
            input=new EditBox(font,104,27,164,16,Component.literal("Search storage or enter a setting"));
            input.setMaxLength(128);input.setValue(previous);input.setHint(Component.literal("Search / settings input"));input.setResponder(v->debounce=4);
        }
        private void send(String action,String text,int value){
            if(activeScreen())PacketDistributor.sendToServer(new Packets.Action(menu.containerId,menu.session(),menu.revision(),action,text,value));
        }
        @Override protected void containerTick(){
            if (!activeScreen()) return;
            super.containerTick();ticks++;ClientInteriors.tick();reflow();
            if(debounce>0&&--debounce==0&&!input.getValue().equals(sent)){sent=input.getValue();pendingRow=-1;send("text",sent,0);}
            if(menu.storageView()){
                if(pendingRow<0&&layout.rows()!=menu.visibleRows()){pendingRow=menu.firstSlot()/9;pendingRows=layout.rows();}
                if(pendingRow>=0){
                    int row=Math.max(0,Math.min(pendingRow,dev.anya.storage.ui.StorageGrid.maxStartRow(menu.resultCount(),pendingRows)));
                    if(row==menu.firstSlot()/9&&pendingRows==menu.visibleRows())pendingRow=-1;
                    else if(ticks-lastViewport>=4){send("viewport","",(row<<3)|pendingRows);lastViewport=ticks;}
                }
            }else pendingRow=-1;
        }
        private void scrollTo(int row){pendingRows=layout.rows();pendingRow=Math.max(0,Math.min(row,dev.anya.storage.ui.StorageGrid.maxStartRow(menu.resultCount(),pendingRows)));}
        private void scrollbar(double y){
            var box=layout.scroll();int thumb=thumbHeight();double distance=Math.max(1,box.height()-thumb);
            int max=dev.anya.storage.ui.StorageGrid.maxStartRow(menu.resultCount(),layout.rows());scrollTo((int)Math.round((y-box.y()-thumb*.5)/distance*max));
        }
        private static final StorageGrid.Box TOOLTIP_BUTTON=new StorageGrid.Box(274,11,96,16);
        private int thumbHeight(){var box=layout.scroll();return Math.max(8,(int)(box.height()*Math.min(1,layout.rows()/(double)Math.max(1,dev.anya.storage.ui.StorageGrid.rows(menu.resultCount())))));}
        private int shown(){return menu.storageView()?Math.min(menu.shownSlots(),layout.rows()*9):45;}
        private int slotAt(double x,double y){
            if(menu.accessView()){
                for(int i=28;i<=34;i++)if(!menu.getSlot(i).getItem().isEmpty()&&memberBox(i).contains(x,y))return i;
            }
            int cell=layout.storageAt(x,y,shown());
            if(cell>=0&&(!menu.accessView()||cell<18)&&(menu.storageView()||!menu.getSlot(cell).getItem().isEmpty()))return cell;
            int inv=layout.inventoryAt(x,y);if(inv>=0)return 54+inv;
            for(int i=0;i<9;i++)if(layout.control(i).contains(x,y)&&!menu.getSlot(45+i).getItem().isEmpty())return 45+i;
            return -1;
        }
        @Override public boolean mouseClicked(double mx,double my,int button){
            if (blockTabletInput()) {
                if (minecraft != null && minecraft.options.keyInventory.matchesMouse(button)) onClose();
                return true;
            }
            reflow();double x=layout.x(mx),y=layout.y(my);
            if(input.mouseClicked(x,y,button)){input.setFocused(true);return true;}input.setFocused(false);
            if(button==0 && TOOLTIP_BUTTON.contains(x,y)){
                press(94);send("tooltips","",menu.tooltipsEnabled()?0:1);return true;
            }
            if(button==0&&x>=12&&x<90){
                if(y>=153&&y<171&&menu.backpackView()){press(90);send("appearance","",0);return true;}
                if(y>=176&&y<194){press(91);ClientLooks.options.particles=!ClientLooks.options.particles;ClientLooks.saveOptions();return true;}
                if(y>=199&&y<217){press(92);ClientLooks.options.images=!ClientLooks.options.images;ClientLooks.saveOptions();return true;}
                if(y>=222&&y<240){press(93);ClientLooks.options.models=!ClientLooks.options.models;ClientLooks.saveOptions();return true;}
            }
            if(menu.storageView()&&layout.scroll().contains(x,y)&&button==0){dragging=true;scrollbar(y);return true;}
            int index=slotAt(x,y);
            if(index>=0&&button>=0&&button<=2){press(index);send("slot","",index|((button&3)<<8)|(hasShiftDown()?65536:0));return true;}
            return false;
        }
        @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(blockTabletInput())return true;if(dragging&&button==0){scrollbar(layout.y(y));return true;}return false;}
        @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return blockTabletInput();}
        @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
            if (blockTabletInput()) return true;
            reflow();double x=layout.x(mx),y=layout.y(my);
            if(menu.storageView()&&x>=102&&x<280&&y>=48&&y<50+layout.rows()*18&&vertical!=0){int row=pendingRow<0?menu.firstSlot()/9:pendingRow;scrollTo(row-(vertical>0?1:-1));return true;}return false;
        }
        @Override protected void slotClicked(Slot slot,int id,int button,ClickType type){} // No vanilla cursor transactions.
        @Override public boolean charTyped(char character,int modifiers){return blockTabletInput()||input.isFocused()&&input.charTyped(character,modifiers);}
        @Override public boolean keyPressed(int key,int scan,int modifiers){
            if(key==256){onClose();return true;}
            if (blockTabletInput()) {
                if (minecraft != null && minecraft.options.keyInventory.matches(key,scan)) onClose();
                return true;
            }
            if(input.isFocused()){
                if(key==257||key==335){sent=input.getValue();debounce=0;pendingRow=-1;send("text",sent,0);return true;}
                input.keyPressed(key,scan,modifiers);return true;
            }
            if(minecraft.options.keyInventory.matches(key,scan)){onClose();return true;}
            if(menu.storageView()&&(key==264||key==265)){scrollTo(menu.firstSlot()/9+(key==264?1:-1));return true;}
            return false;
        }
        @Override public void onFilesDrop(List<Path> paths){if(!blockTabletInput()&&menu.backpackView())ClientInteriors.drop(paths);}
        @Override public void onClose() {
            if (closeRequested || minecraft == null || minecraft.screen != this) return;
            closeRequested = true;
            boolean ownsMenu = activeScreen();
            if (ownsMenu && showTabletFrame()) tabletBoot.returnToHand(openingPlayer, width, height);
            retire();
            if (ownsMenu) super.onClose();
            else minecraft.setScreen(null); // Dismiss only this stale surface; its replacement owns the container.
        }
        @Override public void removed() {
            if (cleaned) return;
            cleaned = true;
            retire();
            super.removed();
            var look = ClientInteriors.current;
            if (minecraft.player != null && minecraft.player.containerMenu instanceof StoreMenu next
                    && (next != menu || minecraft.screen instanceof Screen other && other != this)
                    && look != null && look.menu() == next.containerId && look.session() == next.session()) return;
            ClientInteriors.clear();
        }
        @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){}
        private int[] palette(){
            var look=ClientInteriors.current;String theme=look==null?"atelier":look.interior();
            if(look!=null&&theme.equals("botanical")&&look.texture().contains("cherry"))return new int[]{0xff352835,0xff503a4e,0xffeac1ce};
            if(look!=null&&theme.equals("midnight")&&look.texture().equals("magma"))return new int[]{0xff32252e,0xff4b353f,0xffedb783};
            return switch(theme){
                case "botanical"->new int[]{0xff1d302b,0xff2c4439,0xffb8d59b};
                case "ocean"->new int[]{0xff18303e,0xff284552,0xffa3d9e2};
                case "circuit"->new int[]{0xff182b36,0xff28414c,0xff87d9d9};
                case "midnight"->new int[]{0xff242235,0xff363047,0xffbaacd7};
                case "galaxy"->new int[]{0xff24233c,0xff36314f,0xffc9b6e5};
                default->new int[]{0xff302a38,0xff473849,0xffe2b7cc};
            };
        }
        private void frame(GuiGraphics g,dev.anya.storage.ui.StorageGrid.Box box,int fill,int edge){
            g.fill(box.x()-1,box.y()-1,box.x()+box.width()+1,box.y()+box.height()+1,edge);
            g.fill(box.x(),box.y(),box.x()+box.width(),box.y()+box.height(),fill);
        }
        private StorageGrid.Box memberBox(int slot){
            if(slot==34)return layout.control(4);
            int index=slot-28;
            return new StorageGrid.Box(104+(index%2)*86,86+(index/2)*19,83,17);
        }
        private void press(int slot){pressedSlot=slot;pressedUntil=net.minecraft.Util.getMillis()+160;}
        private boolean pressed(int slot){return slot==pressedSlot&&net.minecraft.Util.getMillis()<pressedUntil;}
        private void option(GuiGraphics g,int slot,ControlGlyph glyph,String name,int y,ControlGlyph.State state,int mx,int my){
            ControlSprites.button(g,font,glyph,state,12,y,78,18,name,mx>=12&&mx<90&&my>=y&&my<y+18,pressed(slot));
        }
        private void drawSlot(GuiGraphics g,int slot,StorageGrid.Box box,boolean hover,String label,int[] colors){
            var stack=menu.getSlot(slot).getItem();
            ControlGlyph.Face face=menu.controlSlot(slot)?ControlGlyph.read(stack):null;
            if(face!=null){
                ControlSprites.button(g,font,face.glyph(),face.state(),box.x(),box.y(),box.width(),box.height(),label,hover,pressed(slot));
            }else{
                frame(g,box,hover?0xff514954:0xff18252a,colors[1]);
                if(!stack.isEmpty()){
                    g.renderItem(stack,box.x(),box.y());
                    g.renderItemDecorations(font,stack,box.x(),box.y());
                }
            }
        }
        private String actionLabel(int slot,ItemStack stack){
            if(menu.storageView()){
                ControlGlyph.Face face=ControlGlyph.read(stack);
                if(face!=null)return switch(face.glyph()){
                    case SCROLL_UP->"Scroll up";case SCROLL_DOWN->"Scroll down";case ORGANIZE->"Organize";
                    case QUICKSTACK->"QuickStack";case DUMP->"Dump";case PROTECT->"Lock slots";case UPGRADES->"Upgrades";
                    case BASE->"Base";case SYMBOL->"Symbol";case EXIT->"Pocket";case POCKET_EDIT->"Upgrades";
                    case FAVORITES->"Favorites";case INFO->"Field kit";
                    default->stack.getHoverName().getString();
                };
            }
            if(menu.accessView())return switch(slot){
                case 46->"Previous";case 47->"Next";case 50->"Select/add";default->stack.getHoverName().getString();
            };
            return stack.getHoverName().getString();
        }
        private void controlTooltip(GuiGraphics g,ItemStack stack,int x,int y){
            java.util.ArrayList<Component> lines=new java.util.ArrayList<>();
            lines.add(stack.getHoverName());
            ItemLore lore=stack.get(DataComponents.LORE);
            if(lore!=null)lines.addAll(lore.lines());
            g.renderComponentTooltip(font,lines,x,y);
        }
        private void drawPocketPadSummary(GuiGraphics graphics, int y, int color) {
            int slot = StoreMenu.POCKET_ACTION_SLOT;
            if (!menu.storageView() || !menu.remoteOpening() || menu.privateView() || !menu.controlSlot(slot)) return;
            ItemStack control = menu.getSlot(slot).getItem();
            ControlGlyph.Face face = ControlGlyph.read(control);
            if (face == null || face.glyph() != ControlGlyph.POCKET_EDIT) return;
            ItemLore lore = control.get(DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                String summary = font.plainSubstrByWidth(lore.lines().get(0).getString(), 170);
                graphics.drawString(font, summary, StorageGrid.Layout.GRID_X, y, color, false);
            }
        }
        @Override public void render(GuiGraphics g,int mx,int my,float partial){
            // The existing screenshot fixture delegates paint with a synthetic inventory at the title screen.
            boolean fixture = openingPlayer == null && minecraft.player == null && !cleaned && Boolean.getBoolean("anyastorage.clientSmoke");
            if (!fixture && (!activeScreen() || drawTabletIntro(g))) return;
            reflow();int x=(int)layout.x(mx),y=(int)layout.y(my);int[] colors=palette();
            if (showTabletFrame()) {
                g.fill(0,0,width,height,-900,0x990c1017);
                tabletBoot.drawSettled(g,width,height,openingPlayer,menu.creativeTabletOpening());
            } else g.fill(0,0,width,height,0x990c1017);
            g.pose().pushPose();g.pose().translate(layout.left(),layout.top(),0);g.pose().scale((float)layout.scale(),(float)layout.scale(),1);
            g.fill(0,0,382,layout.height(),0xff111823);g.fill(2,2,380,layout.height()-2,colors[0]);g.fill(8,8,374,10,colors[2]);
            var look=ClientInteriors.current;
            String header = title.getString();
            if (menu.backpackView() && look != null && look.menu()==menu.containerId && look.session()==menu.session() && !look.bagTitle().isEmpty()) header = look.bagTitle();
            g.drawString(font,font.plainSubstrByWidth(menu.accessView()?"Member permissions":header,250),12,15,0xfff4eaf1,false);
            boolean tips=menu.tooltipsEnabled();
            ControlSprites.button(g,font,ControlGlyph.INFO,tips?ControlGlyph.State.ON:ControlGlyph.State.OFF,
                TOOLTIP_BUTTON.x(),TOOLTIP_BUTTON.y(),TOOLTIP_BUTTON.width(),TOOLTIP_BUTTON.height(),tips?"Tooltips: On":"Tooltips: Off",TOOLTIP_BUTTON.contains(x,y),pressed(94));
            g.drawString(font,"ACTIONS",290,29,colors[2],false);input.render(g,x,y,partial);
            String texture=look==null?"plain":look.texture();
            g.fill(12,35,90,126,colors[1]);ResourceLocation custom=ClientInteriors.texture();
            if(menu.accessView()){
                g.pose().pushPose();g.pose().translate(21,49,0);g.pose().scale(3.7f,3.7f,1);ControlSprites.sprite(g,ControlGlyph.MEMBERS,0,0);g.pose().popPose();
                g.drawCenteredString(font,"Home base",51,112,0xffe6cddd);
                ItemStack member=menu.getSlot(27).getItem();
                g.drawString(font,font.plainSubstrByWidth(member.isEmpty()?"Select a member":member.getHoverName().getString(),78),12,131,colors[2],false);
            }else{
                if(custom!=null){int[] size=ClientInteriors.size();float scale=Math.min(74f/size[0],83f/size[1]);g.pose().pushPose();g.pose().translate(51-size[0]*scale/2,80-size[1]*scale/2,0);g.pose().scale(scale,scale,1);g.blit(custom,0,0,0,0,size[0],size[1],size[0],size[1]);g.pose().popPose();}
                else{g.pose().pushPose();g.pose().translate(17,43,0);g.pose().scale(4.2f,4.2f,1);g.renderItem(ClientLooks.shell(texture),0,0);g.pose().popPose();}
                g.drawString(font,font.plainSubstrByWidth(Looks.title(texture),78),12,131,colors[2],false);
            }
            option(g,90,ControlGlyph.WARDROBE,"Wardrobe",153,menu.backpackView()?ControlGlyph.State.NORMAL:ControlGlyph.State.DISABLED,x,y);
            option(g,91,ControlGlyph.PARTICLES,"Particles",176,ClientLooks.options.particles?ControlGlyph.State.ON:ControlGlyph.State.OFF,x,y);
            option(g,92,ControlGlyph.IMAGE,"Images",199,ClientLooks.options.images?ControlGlyph.State.ON:ControlGlyph.State.OFF,x,y);
            option(g,93,ControlGlyph.MODELS,"Models",222,ClientLooks.options.models?ControlGlyph.State.ON:ControlGlyph.State.OFF,x,y);
            int hover=slotAt(x,y);
            for(int i=0;i<shown();i++){
                var stack=menu.getSlot(i).getItem();
                if(!menu.storageView()&&stack.isEmpty()||menu.accessView()&&i==27)continue;
                boolean permission=menu.accessView()&&i>=28&&i<=34;
                String label=permission?(i==34?"Remove":PERMISSION_LABELS[i-28]):"";
                drawSlot(g,i,permission?memberBox(i):layout.storage(i),hover==i,label,colors);
            }
            if(menu.privateView())g.drawWordWrap(font,Component.literal("Contents are private. Click your inventory to deposit when permitted."),104,53,160,colors[2]);
            else if(menu.storageView()&&menu.resultCount()==0)g.drawString(font,"No matching items",104,55,colors[2],false);
            if(menu.storageView()){
                var box=layout.scroll();g.fill(box.x(),box.y(),box.x()+box.width(),box.y()+box.height(),0xff18252a);
                int max=dev.anya.storage.ui.StorageGrid.maxStartRow(menu.resultCount(),layout.rows()),thumb=thumbHeight();
                int row=pendingRow<0?menu.firstSlot()/9:pendingRow;int sy=box.y()+(max==0?0:(int)((box.height()-thumb)*Math.min(1,row/(double)max)));
                g.fill(box.x()+1,sy,box.x()+box.width()-1,sy+thumb,colors[2]);
            }
            for(int i=0;i<9;i++){
                var stack=menu.getSlot(45+i).getItem();if(stack.isEmpty())continue;var box=layout.control(i);
                drawSlot(g,45+i,box,hover==45+i,actionLabel(45+i,stack),colors);
            }
            int iy=layout.inventoryY();String summary=menu.privateView()?"Private contents":menu.storageView()?(menu.filteredView()?menu.resultCount()+" matches / "+menu.capacity()+" slots":menu.occupiedSlots()+" occupied / "+menu.capacity()+" slots"):"Your inventory";
            g.drawString(font,font.plainSubstrByWidth(summary,170),104,iy-19,colors[2],false);
            drawPocketPadSummary(g, iy - 10, colors[2]);
            long remaining=menu.transferRemainders();
            for(int i=0;i<36;i++){
                var box=layout.inventory(i);
                boolean left=(remaining & (1L << menu.getSlot(54+i).getContainerSlot()))!=0;
                frame(g,box,hover==54+i?0xff53605c:left?0xff52452b:0xff202733,left?0xffffc575:colors[1]);var stack=menu.getSlot(54+i).getItem();
                if(!stack.isEmpty()){g.renderItem(stack,box.x(),box.y());g.renderItemDecorations(font,stack,box.x(),box.y());}
            }
            g.drawString(font,menu.accessView()?"Choose a member to edit rights.":remaining!=0?"Amber = items left in your pack":"Left: stack / Right: one",104,layout.height()-19,0xffc6c6c8,false);
            g.drawString(font,menu.accessView()?"Changes apply immediately.":"Inventory: deposit. Wheel: scroll.",104,layout.height()-10,0xffb2b9c2,false);
            drawStorageCameo(g);
            g.pose().popPose();
            if(!tips)return;
            if(menu.accessView()&&x>=12&&x<90&&y>=129&&y<145&&!menu.getSlot(27).getItem().isEmpty()){
                controlTooltip(g,menu.getSlot(27).getItem(),mx,my);
            }else if(hover>=0){var stack=menu.getSlot(hover).getItem();if(!stack.isEmpty()){
                if(menu.controlSlot(hover))controlTooltip(g,stack,mx,my);
                else g.renderTooltip(font,stack,mx,my);
            }else if(hover<45&&menu.storageView())g.renderTooltip(font,Component.literal("Empty real slot "+(menu.firstSlot()+hover+1)+". Click your inventory to deposit."),mx,my);}
        }
    }
}
