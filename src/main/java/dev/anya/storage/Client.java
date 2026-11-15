package dev.anya.storage;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;
import dev.anya.storage.appearance.*;
import java.nio.file.Path;
import java.util.List;

@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class Client {
    private static final KeyMapping QUICK=new KeyMapping("key.anyastorage.quick",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping DUMP=new KeyMapping("key.anyastorage.dump",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping BAG=new KeyMapping("key.anyastorage.bag",net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,org.lwjgl.glfw.GLFW.GLFW_KEY_B,"key.categories.anyastorage");
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) { event.register(Content.MENU.get(),Screen::new); }
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(QUICK);event.register(DUMP);event.register(BAG); }
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
            boolean quick=false,dump=false,bag=false;while(QUICK.consumeClick())quick=true;while(DUMP.consumeClick())dump=true;while(BAG.consumeClick())bag=true;
            var mc=Minecraft.getInstance();if(mc.player==null){warned="";return;}if(mc.screen!=null)return;
            KeyMapping conflict=bagConflict();String signature=conflict==null?"":BAG.getKey().getName()+"/"+conflict.getName();
            if(!signature.equals(warned)){warned=signature;if(conflict!=null)mc.player.displayClientMessage(Component.literal("Anya backpack key conflicts with ").append(Component.translatable(conflict.getName())).append(". Rebind Open Anya Backpack in Controls; no keys were changed."),false);}
            if(quick)sendKey("quick");if(dump)sendKey("dump");if(bag&&conflict==null)sendKey("bag");
        }
        private static void sendKey(String action){PacketDistributor.sendToServer(new Packets.Action(-1,0,0,action,"",0));}
    }
    /** Manual drawing and hit testing avoid vanilla cursor prediction over display-only slots. */
    public static final class Screen extends AbstractContainerScreen<StoreMenu> {
        private EditBox input;
        private int debounce,ticks,pendingRow=-1,pendingRows=5,lastViewport=-20;
        private String sent="";
        private boolean dragging;
        private dev.anya.storage.ui.StorageGrid.Layout layout;
        public Screen(StoreMenu menu,Inventory inventory,Component title){super(menu,inventory,title);}
        private void reflow(){layout=dev.anya.storage.ui.StorageGrid.layout(width,height,menu.resultCount(),menu.storageView());}
        @Override protected void init(){
            super.init();reflow();String previous=input==null?sent:input.getValue();
            input=new EditBox(font,104,27,164,16,Component.literal("Search storage or enter a setting"));
            input.setMaxLength(128);input.setValue(previous);input.setHint(Component.literal("Search / settings input"));input.setResponder(v->debounce=4);
        }
        private void send(String action,String text,int value){
            if(minecraft.player!=null)PacketDistributor.sendToServer(new Packets.Action(menu.containerId,menu.session(),menu.revision(),action,text,value));
        }
        @Override protected void containerTick(){
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
        private int thumbHeight(){var box=layout.scroll();return Math.max(8,(int)(box.height()*Math.min(1,layout.rows()/(double)Math.max(1,dev.anya.storage.ui.StorageGrid.rows(menu.resultCount())))));}
        private int shown(){return menu.storageView()?Math.min(menu.shownSlots(),layout.rows()*9):45;}
        private int slotAt(double x,double y){
            int cell=layout.storageAt(x,y,shown());if(cell>=0&&(menu.storageView()||!menu.getSlot(cell).getItem().isEmpty()))return cell;
            int inv=layout.inventoryAt(x,y);if(inv>=0)return 54+inv;
            for(int i=0;i<9;i++)if(layout.control(i).contains(x,y)&&!menu.getSlot(45+i).getItem().isEmpty())return 45+i;
            return -1;
        }
        @Override public boolean mouseClicked(double mx,double my,int button){
            reflow();double x=layout.x(mx),y=layout.y(my);
            if(input.mouseClicked(x,y,button)){input.setFocused(true);return true;}input.setFocused(false);
            if(button==0&&x>=12&&x<90){
                if(y>=153&&y<171&&menu.backpackView()){send("appearance","",0);return true;}
                if(y>=176&&y<194){ClientLooks.options.particles=!ClientLooks.options.particles;ClientLooks.saveOptions();return true;}
                if(y>=199&&y<217){ClientLooks.options.images=!ClientLooks.options.images;ClientLooks.saveOptions();return true;}
                if(y>=222&&y<240){ClientLooks.options.models=!ClientLooks.options.models;ClientLooks.saveOptions();return true;}
            }
            if(menu.storageView()&&layout.scroll().contains(x,y)&&button==0){dragging=true;scrollbar(y);return true;}
            int index=slotAt(x,y);
            if(index>=0&&button>=0&&button<=2){send("slot","",index|((button&3)<<8)|(hasShiftDown()?65536:0));return true;}
            return false;
        }
        @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(dragging&&button==0){scrollbar(layout.y(y));return true;}return false;}
        @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return false;}
        @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
            double x=layout.x(mx),y=layout.y(my);
            if(menu.storageView()&&x>=102&&x<280&&y>=48&&y<50+layout.rows()*18&&vertical!=0){int row=pendingRow<0?menu.firstSlot()/9:pendingRow;scrollTo(row-(vertical>0?1:-1));return true;}return false;
        }
        @Override protected void slotClicked(Slot slot,int id,int button,ClickType type){} // No vanilla cursor transactions.
        @Override public boolean charTyped(char character,int modifiers){return input.isFocused()&&input.charTyped(character,modifiers);}
        @Override public boolean keyPressed(int key,int scan,int modifiers){
            if(key==256){onClose();return true;}
            if(input.isFocused()){
                if(key==257||key==335){sent=input.getValue();debounce=0;pendingRow=-1;send("text",sent,0);return true;}
                input.keyPressed(key,scan,modifiers);return true;
            }
            if(minecraft.options.keyInventory.matches(key,scan)){onClose();return true;}
            if(menu.storageView()&&(key==264||key==265)){scrollTo(menu.firstSlot()/9+(key==264?1:-1));return true;}
            return false;
        }
        @Override public void onFilesDrop(List<Path> paths){if(menu.backpackView())ClientInteriors.drop(paths);}
        @Override public void removed(){super.removed();ClientInteriors.clear();}
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
        private void option(GuiGraphics g,String name,int y,boolean on,boolean hover,int[] colors){
            g.fill(12,y,90,y+18,hover?0xff53605c:colors[1]);g.fill(12,y,14,y+18,on?colors[2]:0xff756d7c);
            g.drawString(font,name,18,y+5,on?0xfff0ece7:0xffaaa5ae,false);
        }
        @Override public void render(GuiGraphics g,int mx,int my,float partial){
            reflow();int x=(int)layout.x(mx),y=(int)layout.y(my);int[] colors=palette();
            g.fill(0,0,width,height,0x990c1017);g.pose().pushPose();g.pose().translate(layout.left(),layout.top(),0);g.pose().scale((float)layout.scale(),(float)layout.scale(),1);
            g.fill(0,0,382,layout.height(),0xff111823);g.fill(2,2,380,layout.height()-2,colors[0]);g.fill(8,8,374,10,colors[2]);
            g.drawString(font,font.plainSubstrByWidth(title.getString(),250),12,15,0xfff4eaf1,false);
            g.drawString(font,"ACTIONS",290,29,colors[2],false);input.render(g,x,y,partial);
            var look=ClientInteriors.current;String texture=look==null?"plain":look.texture();
            g.fill(12,35,90,126,colors[1]);ResourceLocation custom=ClientInteriors.texture();
            if(custom!=null){int[] size=ClientInteriors.size();float scale=Math.min(74f/size[0],83f/size[1]);g.pose().pushPose();g.pose().translate(51-size[0]*scale/2,80-size[1]*scale/2,0);g.pose().scale(scale,scale,1);g.blit(custom,0,0,0,0,size[0],size[1],size[0],size[1]);g.pose().popPose();}
            else{g.pose().pushPose();g.pose().translate(17,43,0);g.pose().scale(4.2f,4.2f,1);g.renderItem(ClientLooks.shell(texture),0,0);g.pose().popPose();}
            g.drawString(font,font.plainSubstrByWidth(Looks.title(texture),78),12,131,colors[2],false);
            option(g,"Wardrobe",153,menu.backpackView(),x>=12&&x<90&&y>=153&&y<171,colors);
            option(g,"Particles",176,ClientLooks.options.particles,x>=12&&x<90&&y>=176&&y<194,colors);
            option(g,"Images",199,ClientLooks.options.images,x>=12&&x<90&&y>=199&&y<217,colors);
            option(g,"Models",222,ClientLooks.options.models,x>=12&&x<90&&y>=222&&y<240,colors);
            int hover=slotAt(x,y);
            for(int i=0;i<shown();i++){
                var stack=menu.getSlot(i).getItem();if(!menu.storageView()&&stack.isEmpty())continue;
                var box=layout.storage(i);frame(g,box,hover==i?0xff53605c:0xff18252a,colors[1]);
                if(!stack.isEmpty()){g.renderItem(stack,box.x(),box.y());g.renderItemDecorations(font,stack,box.x(),box.y());}
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
                frame(g,box,hover==45+i?0xff53605c:colors[1],colors[0]);g.renderItem(stack,box.x()+2,box.y()+1);
                String label=menu.storageView()?new String[]{"Up","Down","Organize","QuickStack","Dump","Lock slots","Upgrades","Favorites","Info"}[i]:stack.getHoverName().getString();
                if(menu.storageView()&&!menu.backpackView()&&i==6)label="Base";
                g.drawString(font,font.plainSubstrByWidth(label,58),box.x()+21,box.y()+6,0xffeee8e8,false);
            }
            int iy=layout.inventoryY();String summary=menu.privateView()?"Private contents":menu.storageView()?(menu.filteredView()?menu.resultCount()+" matches / "+menu.capacity()+" slots":menu.occupiedSlots()+" occupied / "+menu.capacity()+" slots"):"Your inventory";
            g.drawString(font,font.plainSubstrByWidth(summary,170),104,iy-19,colors[2],false);
            for(int i=0;i<36;i++){
                var box=layout.inventory(i);frame(g,box,hover==54+i?0xff53605c:0xff202733,colors[1]);var stack=menu.getSlot(54+i).getItem();
                if(!stack.isEmpty()){g.renderItem(stack,box.x(),box.y());g.renderItemDecorations(font,stack,box.x(),box.y());}
            }
            g.drawString(font,"Left: stack / Right: one",104,layout.height()-19,0xffc6c6c8,false);
            g.drawString(font,"Inventory: deposit. Wheel: scroll.",104,layout.height()-10,0xffb2b9c2,false);
            g.pose().popPose();
            if(hover>=0){var stack=menu.getSlot(hover).getItem();if(!stack.isEmpty())g.renderTooltip(font,stack,mx,my);else if(hover<45&&menu.storageView())g.renderTooltip(font,Component.literal("Empty real slot "+(menu.firstSlot()+hover+1)+". Click your inventory to deposit."),mx,my);}
        }
    }
}
