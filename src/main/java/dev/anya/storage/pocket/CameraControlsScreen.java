package dev.anya.storage.pocket;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Per-device controls. Acknowledged state always comes back from the server. */
final class CameraControlsScreen extends Screen {
    private static final String[] MODES={"Fixed","Occasional glances","Follow motion"};
    private static final String[] GROUPS={"All mobs","Hostile mobs","Watched types only"};
    private record Kind(String id,String name){}
    private CameraConsole.View v;
    private final List<Kind> kinds=new ArrayList<>();
    private final List<AbstractWidget> rows=new ArrayList<>();
    private List<Kind> results=List.of();
    private Set<String> watch=Set.of(),ignore=Set.of();
    private String query="";
    private int offset,left,panel,listTop,page;
    private boolean waiting;
    private EditBox search;
    private Button prev,next;
    CameraControlsScreen(CameraConsole.View view){
        super(Component.literal("Camera settings"));v=view;
        for(var type:BuiltInRegistries.ENTITY_TYPE){
            var id=BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if(type.getCategory()!=MobCategory.MISC||id.toString().equals("cobblemon:pokemon"))
                kinds.add(new Kind(id.toString(),type.getDescription().getString()));
        }
        kinds.sort(Comparator.comparing(Kind::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Kind::id));
    }
    static void receive(CameraConsole.View v){
        var mc=Minecraft.getInstance();
        if(v.opening())mc.setScreen(new CameraControlsScreen(v));
        else if(mc.screen instanceof CameraControlsScreen s&&s.v.token().equals(v.token())){
            s.v=v;s.waiting=false;s.rebuildWidgets();
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){if(minecraft.player==null||!minecraft.player.isAlive())onClose();}
    private void change(String control,int value,String id){
        if(waiting)return;
        waiting=true;PacketDistributor.sendToServer(new CameraConsole.Edit(v.token(),v.source(),control,value,id));
    }
    private Button button(int x,int y,int w,String name,Runnable act){
        return addRenderableWidget(Button.builder(Component.literal(name),b->act.run()).bounds(x,y,w,18).build());
    }
    @Override protected void init(){
        rows.clear();search=null;panel=Math.min(width-16,336);left=(width-panel)/2;int y=25;
        if(v.width()>0){
            int half=(panel-68)/2;
            button(left,y,half,"Width: "+v.width()+" blocks",()->change("width",v.width()%v.maxW()+1,""));
            button(left+half+4,y,half,"Height: "+v.height()+" blocks",()->change("height",v.height()%v.maxH()+1,""));
            button(left+panel-60,y,60,"Fit wall",()->change("fit",0,""));
            y+=21;
        }
        if(v.mode()>=0){
            int half=(panel-4)/2;
            button(left,y,half,"Aim: "+MODES[v.mode()],()->change("mode",(v.mode()+1)%MODES.length,""));
            button(left+half+4,y,panel-half-4,"Targets: "+GROUPS[v.group()],()->change("group",(v.group()+1)%GROUPS.length,""));
            y+=22;
            if(v.lens()>=0){
                button(left,y,panel,v.lens()==1?"Wide lens (104°)":"Standard lens (90°)",()->change("lens",1-v.lens(),""))
                        .setTooltip(Tooltip.create(Component.literal("Switch between the standard view and a wider view of the same scene.")));
                y+=21;
            }
            search=new EditBox(font,left,y,panel-48,18,Component.literal("Find a mob"));
            search.setHint(Component.literal("Find a mob by name or mod"));search.setMaxLength(100);search.setValue(query);
            addRenderableWidget(search);
            prev=button(left+panel-44,y,20,"▲",()->scroll(-page));next=button(left+panel-21,y,21,"▼",()->scroll(page));
            listTop=y+21;page=Math.max(1,(height-listTop-52)/19);
            watch=v.watch().isEmpty()?Set.of():Set.of(v.watch().split(","));
            ignore=v.ignore().isEmpty()?Set.of():Set.of(v.ignore().split(","));
            search.setResponder(s->{query=s;offset=0;filter();});filter();
        }
        button(left+panel-60,height-22,60,"Done",this::onClose);
    }
    private void filter(){
        String q=query.trim().toLowerCase(Locale.ROOT);
        results=kinds.stream().filter(k->k.name.toLowerCase(Locale.ROOT).contains(q)||k.id.contains(q)).toList();
        offset=Math.clamp(offset,0,Math.max(0,results.size()-page));drawRows();
    }
    private void scroll(int amount){offset=Math.clamp(offset+amount,0,Math.max(0,results.size()-page));drawRows();}
    private void drawRows(){
        rows.forEach(this::removeWidget);rows.clear();
        prev.active=offset>0;next.active=offset+page<results.size();
        for(int i=0;i<page&&offset+i<results.size();i++){
            var k=results.get(offset+i);int y=listTop+i*19;
            Button include=button(left+panel-112,y,55,watch.contains(k.id)?"✓ Watch":"Watch",()->change("rule",watch.contains(k.id)?0:1,k.id));
            Button exclude=button(left+panel-55,y,55,ignore.contains(k.id)?"✓ Ignore":"Ignore",()->change("rule",ignore.contains(k.id)?0:2,k.id));
            include.setTooltip(Tooltip.create(Component.literal("Watch "+k.name+" even outside the selected group.")));
            exclude.setTooltip(Tooltip.create(Component.literal("Keep "+k.name+" out of camera tracking.")));
            rows.add(include);rows.add(exclude);
        }
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(search!=null&&dy!=0){scroll(dy>0?-1:1);return true;}return super.mouseScrolled(x,y,dx,dy);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        super.render(g,mx,my,pt);g.drawString(font,v.width()>0?"Wall monitor settings":"Outdoor camera settings",left,9,0xfff6e6f1);
        if(v.mode()>=0){
            for(int i=0;i<page&&offset+i<results.size();i++){
                var k=results.get(offset+i);
                g.drawString(font,font.plainSubstrByWidth(k.name,panel-115),left,listTop+5+i*19,0xffefedf4);
            }
        }
        String status=waiting?"Saving…":v.message();
        if(status.isEmpty()&&v.mode()<0&&v.width()>0)status="Link a loaded camera to adjust its targets.";
        var lines=font.split(Component.literal(status),panel);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),left,height-46+i*10,0xffeed2e3);
    }
}
