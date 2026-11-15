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
            super(menu,inventory,title);imageWidth=304;imageHeight=250;titleLabelX=12;titleLabelY=12;inventoryLabelX=125;inventoryLabelY=143;
        }
        @Override protected void init() {
            super.init(); input=new EditBox(font,leftPos+124,topPos+16,166,14,Component.literal("Search or enter a setting"));
            addRenderableWidget(Button.builder(Component.literal("Wardrobe"),b->send("appearance","",0)).bounds(leftPos+12,topPos+146,96,18).build());
            addRenderableWidget(Button.builder(Component.literal("Particles"),b->{ClientLooks.options.particles=!ClientLooks.options.particles;ClientLooks.saveOptions();b.setMessage(Component.literal("Particles: "+(ClientLooks.options.particles?"ON":"OFF")));}).bounds(leftPos+12,topPos+168,96,18).build());
            addRenderableWidget(Button.builder(Component.literal("Private images"),b->{ClientLooks.options.images=!ClientLooks.options.images;ClientLooks.saveOptions();b.setMessage(Component.literal("Images: "+(ClientLooks.options.images?"ON":"OFF")));}).bounds(leftPos+12,topPos+190,96,18).build());
            addRenderableWidget(Button.builder(Component.literal("Wearable models"),b->{ClientLooks.options.models=!ClientLooks.options.models;ClientLooks.saveOptions();b.setMessage(Component.literal("Models: "+(ClientLooks.options.models?"ON":"OFF")));}).bounds(leftPos+12,topPos+212,96,18).build());
            input.setMaxLength(128);input.setHint(Component.literal("Search / settings input"));input.setResponder(value->debounce=4);addRenderableWidget(input);
        }
        private void send(String action,String text,int value) { PacketDistributor.sendToServer(new Packets.Action(menu.containerId,menu.session(),menu.revision(),action,text,value)); }
        @Override protected void containerTick() {
            super.containerTick();ClientInteriors.tick();
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
        @Override public void onFilesDrop(List<Path> paths){ClientInteriors.drop(paths);}
        @Override public void removed(){super.removed();ClientInteriors.clear();}
        @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
            int x=leftPos,y=topPos;var look=ClientInteriors.current;
            String interior=look==null?"atelier":Looks.known(Looks.INTERIORS,look.interior(),"atelier");
            ResourceLocation background=ExpansionPackets.id("textures/gui/"+interior+".png");
            g.blit(background,x,y,0,0,imageWidth,imageHeight,imageWidth,imageHeight);
            ResourceLocation custom=ClientInteriors.texture();
            if(custom!=null){
                int[] size=ClientInteriors.size();float scale=Math.min(96f/size[0],96f/size[1]);
                g.pose().pushPose();g.pose().translate(x+59-size[0]*scale/2,y+86-size[1]*scale/2,0);g.pose().scale(scale,scale,1);g.blit(custom,0,0,0,0,size[0],size[1],size[0],size[1]);g.pose().popPose();
                float sx=172f/size[0],sy=105f/size[1];g.pose().pushPose();g.pose().translate(x+119,y+34,0);g.pose().scale(sx,sy,1);g.blit(custom,0,0,0,0,size[0],size[1],size[0],size[1]);g.pose().popPose();
                g.fill(x+119,y+34,x+291,y+139,0x99221D35);
            }else{
                var shell=ClientLooks.shell(look==null?"plain":look.texture());g.pose().pushPose();g.pose().translate(x+22,y+53,0);g.pose().scale(4.5f,4.5f,1);g.renderItem(shell,0,0);g.pose().popPose();
            }
            for(Slot slot:menu.slots){int sx=x+slot.x,sy=y+slot.y;g.fill(sx-1,sy-1,sx+17,sy+17,0x99312841);g.fill(sx,sy,sx+16,sy+16,0x702A283D);g.fill(sx,sy,sx+16,sy+1,0x99766B87);}
        }
        @Override protected void renderLabels(GuiGraphics g,int mx,int my) {
            g.drawString(font,Component.literal("ANYA / STORAGE"),12,15,0xFFEAD7E7,false);
            g.drawString(font,playerInventoryTitle,125,143,0xFFBDCCDD,false);
            var look=ClientInteriors.current;String caption=look==null?"ATELIER":Looks.title(look.texture());g.drawString(font,font.plainSubstrByWidth(caption,92),12,127,0xFFEAD7E7,false);
        }
        @Override public void render(GuiGraphics g,int mx,int my,float partial) { super.render(g,mx,my,partial);renderTooltip(g,mx,my); }
    }
}
