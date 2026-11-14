package dev.anya.storage;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class StorageScreen extends AbstractContainerScreen<StorageMenu> {
    private EditBox input;
    private int sequence,ticks,epoch=-1;
    private String lastSent="";
    public StorageScreen(StorageMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=198;imageHeight=242;inventoryLabelX=18;inventoryLabelY=145;
    }
    @Override protected void init(){
        super.init();String previous=input==null?"":input.getValue();
        input=new EditBox(font,leftPos+10,topPos+7,178,18,Component.literal("Search or editor input"));
        input.setMaxLength(256);input.setHint(Component.literal("Search / type an editor value"));input.setValue(previous);addRenderableWidget(input);
    }
    private void flush(){
        if(input!=null&&!input.getValue().equals(lastSent)){lastSent=input.getValue();PacketDistributor.sendToServer(new Packets.Input(menu.session,++sequence,lastSent));}
    }
    @Override protected void containerTick(){
        super.containerTick();
        if(epoch!=menu.epoch()){epoch=menu.epoch();input.setValue("");lastSent="";}
        if(++ticks%6==0)flush();
    }
    @Override public boolean mouseClicked(double x,double y,int button){flush();return super.mouseClicked(x,y,button);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){flush();input.setFocused(false);return true;}
        if(input.isFocused()&&key!=GLFW.GLFW_KEY_ESCAPE){input.keyPressed(key,scan,modifiers);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override protected void renderBg(GuiGraphics graphics,float partialTick,int mouseX,int mouseY){
        graphics.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff10222c);
        graphics.fill(leftPos+1,topPos+1,leftPos+imageWidth-1,topPos+2,0xff74d5d5);
        graphics.fill(leftPos+1,topPos+imageHeight-2,leftPos+imageWidth-1,topPos+imageHeight-1,0xff74d5d5);
        for(var slot:menu.slots){int x=leftPos+slot.x-1,y=topPos+slot.y-1;graphics.fill(x,y,x+18,y+18,0xff08161f);graphics.fill(x,y,x+18,y+1,0xff355160);graphics.fill(x,y,x+1,y+18,0xff355160);}
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        graphics.drawString(font,menu.viewMode().name().replace('_',' '),18,26,0xff8edbda,false);
        graphics.drawString(font,playerInventoryTitle,inventoryLabelX,inventoryLabelY,0xffbdced6,false);
        graphics.drawString(font,"Anya Storage  >.<",18,233,0xff7497a6,false);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        super.render(graphics,mouseX,mouseY,partialTick);renderTooltip(graphics,mouseX,mouseY);
    }
}
