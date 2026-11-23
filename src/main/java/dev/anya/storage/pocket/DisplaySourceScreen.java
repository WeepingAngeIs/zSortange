package dev.anya.storage.pocket;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

final class DisplaySourceScreen extends Screen {
    private CameraConsole.DisplayView view;
    private final CameraDevices.Device device;
    private final boolean room;
    private final boolean manageCamera;
    private EditBox url;
    private int left, top, panel, dropY, messageY;
    private String notice="";

    private DisplaySourceScreen(CameraConsole.DisplayView view, CameraDevices.Device device, boolean room, boolean manageCamera) {
        super(label("source")); this.view=view; this.device=device; this.room=room; this.manageCamera=manageCamera;
    }

    static void receive(CameraConsole.DisplayView view, boolean room, boolean manageCamera) {
        var mc=Minecraft.getInstance();
        if(mc.level==null || !(mc.level.getBlockEntity(view.pos()) instanceof CameraDevices.Device device)
                || !(device.getBlockState().getBlock() instanceof WallMonitorBlock)) return;
        if(view.state().opening()) mc.setScreen(new DisplaySourceScreen(view,device,room,manageCamera));
        else if(mc.screen instanceof DisplaySourceScreen open && open.device==device
                && open.view.state().token().equals(view.state().token())) {
            open.view=view;
            open.notice=view.state().message();
        } else CameraControlsScreen.receive(view.state());
    }

    private static Component label(String key,Object... args){return Component.translatable("screen.anyastorage.media."+key,args);}

    @Override protected void init() {
        panel=Math.min(310,width-16); left=(width-panel)/2;
        top=Math.max(2,(height-(manageCamera?177:155))/2);
        int half=(panel-4)/2;
        int y=18;
        if(manageCamera){
            button(0,y,half,"camera",()->{stopForMe();onClose();});
            button(half+4,y,panel-half-4,"link",()->PacketDistributor.sendToServer(
                    new CameraConsole.Edit(view.state().token(),view.state().source(),"link",0,"")));
            y+=22;
        }
        String previous=url==null?"":url.getValue();
        url=new EditBox(font,left,top+y,panel,18,label("url"));
        url.setMaxLength(4096); url.setHint(label("url")); url.setValue(previous); addRenderableWidget(url);
        y+=22;
        button(0,y,half,room?"play_room":"play",()->{
            String address=url.getValue().strip();
            if(address.isEmpty()){notice=label("empty").getString();return;}
            if(address.length()>2048){notice=label("too_long").getString();return;}
            if(room)PacketDistributor.sendToServer(new RoomMediaNet.Play(view.pos(),address));
            else ScreenMedia.play(view.pos(),address);
            url.setValue("");onClose();
        });
        button(half+4,y,panel-half-4,"stop",()->{stopForMe();notice=label("stopped").getString();});
        y+=22;
        var volume=new AbstractSliderButton(left,top+y,panel,18,volumeLabel(ScreenMedia.volume()),ScreenMedia.volume()){
            @Override protected void updateMessage(){setMessage(volumeLabel(value));}
            @Override protected void applyValue(){ScreenMedia.volume((float)value);}
        };
        addRenderableWidget(volume);
        dropY=y+22; y=dropY+14;
        if(manageCamera && (view.state().maxW()>1 || view.state().mode()>=0)){
            button(0,y,half,"controls",()->minecraft.setScreen(new CameraControlsScreen(view.state())));
            button(half+4,y,panel-half-4,"done",this::onClose);
        }else button(0,y,panel,"done",this::onClose);
        messageY=y+22;
    }
    private static Component volumeLabel(double value){return label("volume",Math.round(value*100));}
    private void stopForMe(){RoomMediaClient.stopForMe(view.pos());}
    private void button(int x,int y,int w,String key,Runnable action) {
        addRenderableWidget(Button.builder(label(key),b->action.run()).bounds(left+x,top+y,w,18).build());
    }
    @Override public void onFilesDrop(List<Path> files) {
        if(files.size()!=1 || !files.getFirst().getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gif")){
            notice=label("one_gif").getString();return;
        }
        ScreenMedia.playFile(view.pos(),files.getFirst());onClose();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){
        if(minecraft.level==null || device.isRemoved() || minecraft.player==null || !minecraft.player.isAlive()
                || device.getLevel()!=minecraft.level || minecraft.level.getBlockEntity(view.pos())!=device) onClose();
    }
    @Override public void render(GuiGraphics g,int x,int y,float pt){
        super.render(g,x,y,pt);
        g.drawString(font,title,left,top+4,0xfff3e1f2);
        g.drawString(font,label("drop"),left,top+dropY,0xffdbccdf);
        String message=notice.isEmpty()?ScreenMedia.status(view.pos()):notice;
        var lines=font.split(Component.literal(message),panel);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),left,top+messageY+i*10,0xfff0d6de);
    }
}
