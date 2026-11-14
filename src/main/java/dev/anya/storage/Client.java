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

@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class Client {
    private static final KeyMapping QUICK=new KeyMapping("key.anyastorage.quick",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping DUMP=new KeyMapping("key.anyastorage.dump",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    private static final KeyMapping BAG=new KeyMapping("key.anyastorage.bag",InputConstants.UNKNOWN.getValue(),"key.categories.anyastorage");
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) { event.register(Content.MENU.get(),Screen::new); }
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(QUICK);event.register(DUMP);event.register(BAG); }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) { event.registerBlockEntityRenderer(Content.STORAGE.get(),ChestRenderer::new); }
    @EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
    public static final class Tick {
        @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
            if(Minecraft.getInstance().player==null||Minecraft.getInstance().screen!=null) return;
            while(QUICK.consumeClick()) sendKey("quick"); while(DUMP.consumeClick()) sendKey("dump");while(BAG.consumeClick()) sendKey("bag");
        }
        private static void sendKey(String action) { PacketDistributor.sendToServer(new Packets.Action(-1,0,0,action,"",0)); }
    }
    public static final class Screen extends AbstractContainerScreen<StoreMenu> {
        private EditBox input; private int debounce; private String sent="";
        public Screen(StoreMenu menu,Inventory inventory,Component title) {
            super(menu,inventory,title);imageWidth=194;imageHeight=240;titleLabelX=12;titleLabelY=8;inventoryLabelX=12;inventoryLabelY=143;
        }
        @Override protected void init() {
            super.init(); input=new EditBox(font,leftPos+12,topPos+18,170,14,Component.literal("Search or enter a setting"));
            input.setMaxLength(128);input.setHint(Component.literal("Search / settings input"));input.setResponder(value->debounce=4);addRenderableWidget(input);
        }
        private void send(String action,String text,int value) { PacketDistributor.sendToServer(new Packets.Action(menu.containerId,menu.session(),menu.revision(),action,text,value)); }
        @Override protected void containerTick() {
            super.containerTick();
            if(debounce>0 && --debounce==0 && !input.getValue().equals(sent)) { sent=input.getValue();send("text",sent,0); }
        }
        @Override protected void slotClicked(Slot slot,int slotId,int mouseButton,ClickType type) {
            if(slotId<0) return;
            // Display slots are server-rendered buttons, never client-predicted inventory moves.
            if(type==ClickType.PICKUP || type==ClickType.QUICK_MOVE || type==ClickType.CLONE)
                send("slot","",slotId | ((mouseButton&3)<<8) | (type==ClickType.QUICK_MOVE?65536:0));
        }
        @Override public boolean keyPressed(int key,int scan,int modifiers) {
            if(input.isFocused() && key!=256) {
                if(key==257||key==335) { sent=input.getValue();debounce=0;send("text",sent,0);return true; }
                input.keyPressed(key,scan,modifiers);
                return true; // Typing must not trigger a rebound inventory-close key.
            }
            return super.keyPressed(key,scan,modifiers);
        }
        @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
            int x=leftPos,y=topPos;
            g.fill(x,y,x+imageWidth,y+imageHeight,0xFF171C2B);g.fill(x+2,y+2,x+imageWidth-2,y+imageHeight-2,0xFF252B40);
            g.fill(x+2,y+2,x+imageWidth-2,y+4,0xFFEE9CC4);
            for(Slot slot:menu.slots) { int sx=x+slot.x,sy=y+slot.y;g.fill(sx-1,sy-1,sx+17,sy+17,0xFF101623);g.fill(sx,sy,sx+16,sy+16,0xFF384359); }
        }
        @Override protected void renderLabels(GuiGraphics g,int mx,int my) {
            g.drawString(font,title,12,8,0xFFEAD7E7,false);g.drawString(font,playerInventoryTitle,12,143,0xFFBDCCDD,false);
        }
        @Override public void render(GuiGraphics g,int mx,int my,float partial) { super.render(g,mx,my,partial);renderTooltip(g,mx,my); }
    }
}
