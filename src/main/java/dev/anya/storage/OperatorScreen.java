package dev.anya.storage;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid="anyastorage",value=Dist.CLIENT)
public final class OperatorScreen extends Screen {
    private static int sequence;
    private final Screen parent;
    private EditBox search;
    private OperatorPage.View view;
    private int ticket,offset,delay,visible,left,panel;
    private boolean waiting;
    private String query="",pendingValue="",status="";
    private final List<AbstractWidget> controls=new ArrayList<>();
    private final List<String> labels=new ArrayList<>();
    static {OperatorPage.receiver=v->{if(Minecraft.getInstance().screen instanceof OperatorScreen s)s.receive(v);};}
    public OperatorScreen(Screen parent){super(Component.literal("Storage server settings"));this.parent=parent;}
    @SubscribeEvent
    public static void entry(ScreenEvent.Init.Post e){
        var mc=Minecraft.getInstance();
        if(!(e.getScreen() instanceof PauseScreen) || mc.player==null || !mc.player.hasPermissions(2))return;
        e.addListener(Button.builder(Component.literal("Storage server settings"),b->mc.setScreen(new OperatorScreen(e.getScreen())))
                .bounds(5,5,140,20).build());
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override protected void init(){
        panel=Math.min(540,width-16);left=(width-panel)/2;visible=Math.clamp((height-106)/23,1,16);
        controls.clear();labels.clear();
        search=addRenderableWidget(new EditBox(font,left,25,panel,20,Component.literal("Search settings")));
        search.setHint(Component.literal("Search settings"));search.setMaxLength(80);search.setValue(query);
        search.setResponder(v->{query=v;offset=0;delay=4;invalidate();});
        setInitialFocus(search);request("","",false);
    }
    private void invalidate(){waiting=true;ticket=++sequence;controls.forEach(w->w.active=false);}
    private void request(String key,String value,boolean confirm){
        invalidate();delay=0;
        PacketDistributor.sendToServer(new OperatorPage.Request(ticket,view==null?-1:view.revision(),query,offset,visible,key,value,confirm));
    }
    private void receive(OperatorPage.View v){
        if(ticket!=v.ticket())return;
        waiting=false;view=v;offset=v.offset();status=v.status();
        for(var w:controls)removeWidget(w);
        controls.clear();labels.clear();
        int y=50;
        int controlWidth=Math.min(154,panel/2);
        for(var row:v.cells()){
            int x=left+panel-controlWidth;
            labels.add(row.label());
            if(row.toggle() || row.value().equals("Apply") && row.key().startsWith("preset/") || row.value().equals("Remove")&&(row.key().startsWith("species/")||row.key().startsWith("uploader/"))){
                String value=row.toggle()?Boolean.toString(!Boolean.parseBoolean(row.value())):row.value();
                String caption=row.toggle()?(Boolean.parseBoolean(row.value())?"On":"Off"):row.value();
                button(x,y,controlWidth,caption,row.label()+". "+row.help(),()->edit(row,value));
            }else{
                EditBox field=new EditBox(font,x,y,controlWidth-46,20,Component.literal(row.label()));
                field.setMaxLength(256);field.setValue(row.value());field.setTooltip(Tooltip.create(Component.literal(row.help())));
                controls.add(addRenderableWidget(field));button(x+controlWidth-44,y,44,"Save",row.label()+". "+row.help(),()->edit(row,field.getValue()));
            }
            y+=23;
        }
        if(!v.confirmation().isEmpty()){
            button(left,height-24,70,"Confirm","Confirm the pending change.",()->request(v.confirmation(),pendingValue,true));
            button(left+74,height-24,60,"Cancel","Keep current settings.",()->request("","",false));
        }else{
            button(left,height-24,48,"Prev","Previous settings",()->move(-visible)).active=offset>0;
            button(left+51,height-24,48,"Next","More settings",()->move(visible)).active=offset+visible<v.total();
        }
        button(left+panel-52,height-24,52,"Done","Return to the game menu.",this::onClose);
    }
    private Button button(int x,int y,int w,String title,String help,Runnable run){
        var b=Button.builder(Component.literal(title),unused->run.run()).bounds(x,y,w,20)
                .tooltip(Tooltip.create(Component.literal(help))).build();
        controls.add(addRenderableWidget(b));return b;
    }
    private void edit(OperatorPage.Cell row,String value){pendingValue=value;request(row.key(),value,false);}
    private void move(int amount){if(!waiting && view!=null){offset=Math.clamp(offset+amount,0,Math.max(0,view.total()-visible));request("","",false);}}
    @Override public void tick(){
        if(minecraft.player==null || !minecraft.player.hasPermissions(2)){onClose();return;}
        if(delay>0 && --delay==0)request("","",false);
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){if(dy!=0)move(dy>0?-3:3);return true;}
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        super.render(g,x,y,partial);
        g.drawString(font,title,left,10,0xfff9e9fa);
        int labelWidth=panel-Math.min(154,panel/2)-4;
        for(int i=0;i<labels.size();i++)g.drawString(font,font.plainSubstrByWidth(labels.get(i),labelWidth),left,56+i*23,0xfff0e5f1);
        String msg=status.isEmpty()?(waiting?"Loading…":view==null?"":view.total()==0?"No matching settings":(offset+1)+"–"+(offset+labels.size())+" of "+view.total()):status;
        var lines=font.split(Component.literal(msg),panel);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),left,height-46+i*10,0xffffdded);
        if(lines.size()>2 && y>=height-46 && y<height-24)g.renderTooltip(font,Component.literal(msg),x,y);
    }
}
