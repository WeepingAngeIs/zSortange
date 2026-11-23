package dev.anya.storage.pocket;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** External media is opened on this client only, after the player explicitly chooses Play or a file. */
final class DisplaySourceScreen extends Screen {
    private CameraConsole.DisplayView view;
    private final CameraDevices.Device device;
    private EditBox url;
    private int left, top, panel;
    private String notice="";

    private DisplaySourceScreen(CameraConsole.DisplayView view, CameraDevices.Device device) {
        super(label("source")); this.view=view; this.device=device;
    }

    static void receive(CameraConsole.DisplayView view) {
        var mc=Minecraft.getInstance();
        if(mc.level==null || !(mc.level.getBlockEntity(view.pos()) instanceof CameraDevices.Device device)
                || !(device.getBlockState().getBlock() instanceof WallMonitorBlock)) return;
        if(view.state().opening()) mc.setScreen(new DisplaySourceScreen(view,device));
        else if(mc.screen instanceof DisplaySourceScreen open && open.device==device
                && open.view.state().token().equals(view.state().token())) {
            open.view=view;
            open.notice=view.state().message();
        } else CameraControlsScreen.receive(view.state());
    }

    private static Component label(String key){return Component.translatable("screen.anyastorage.media."+key);}

    @Override protected void init() {
        panel=Math.min(310,width-16); left=(width-panel)/2; top=Math.max(6,(height-175)/2);
        int half=(panel-4)/2;
        button(0,24,half,"camera",()->{ScreenMedia.stop(view.pos());onClose();});
        button(half+4,24,panel-half-4,"link",()->PacketDistributor.sendToServer(
                new CameraConsole.Edit(view.state().token(),view.state().source(),"link",0,"")));
        String previous=url==null?"":url.getValue();
        url=new EditBox(font,left,top+51,panel,20,label("url"));
        url.setMaxLength(4096); url.setHint(label("url")); url.setValue(previous); addRenderableWidget(url);
        button(0,75,half,"play",()->{
            if(url.getValue().isBlank()){notice=label("empty").getString();return;}
            ScreenMedia.play(view.pos(),url.getValue().strip());url.setValue("");onClose();
        });
        button(half+4,75,panel-half-4,"stop",()->{ScreenMedia.stop(view.pos());notice=label("stopped").getString();});
        if(view.state().maxW()>1 || view.state().mode()>=0)
            button(0,123,half,"controls",()->minecraft.setScreen(new CameraControlsScreen(view.state())));
        button(half+4,123,panel-half-4,"done",this::onClose);
    }
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
        g.drawString(font,label("drop"),left,top+102,0xffdbccdf);
        String message=notice.isEmpty()?ScreenMedia.status(view.pos()):notice;
        var lines=font.split(Component.literal(message),panel);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),left,top+147+i*10,0xfff0d6de);
    }
}
