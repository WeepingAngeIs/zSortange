package dev.anya.storage;

import dev.anya.storage.appearance.ClientLooks;
import dev.anya.storage.ui.ChestLayout;
import dev.anya.storage.ui.ControlGlyph;
import dev.anya.storage.ui.ControlSprites;
import dev.anya.storage.ui.StorageGrid;
import dev.anya.storage.ui.StorageGrid.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A direct physical chest presents exactly the server's current window into its inventory. */
final class ChestPanel {
    private enum Drawer { CLOSED, SETTINGS, DISPLAY }
    static final Box EMPTY=new Box(0,0,0,0);
    private static final int SEARCH=-2, SETTINGS=-3, DISPLAY=-4, NONE=-1;
    private static final List<Integer> ACTIONS=List.of(47,48,49,50,51,52,53);
    private static final List<Integer> PERSONAL=List.of(200,201,202,203);
    private static final List<String> NAMES=List.of("Tooltips","Particles","Images","Models");
    private static final List<ControlGlyph> GLYPHS=List.of(ControlGlyph.INFO,ControlGlyph.PARTICLES,ControlGlyph.IMAGE,ControlGlyph.MODELS);
    private final Client.Screen screen;
    private final StoreMenu menu;
    private ChestLayout layout;
    private Drawer drawer=Drawer.CLOSED;
    private int requestedRow=-1,lastRequest=-20,paintedRevision=-1,focus=NONE;
    private String pendingText;
    private int textRevision;
    private boolean dragging;

    ChestPanel(Client.Screen screen, StoreMenu menu){this.screen=screen;this.menu=menu;}
    void reset(){drawer=Drawer.CLOSED;requestedRow=-1;focus=NONE;dragging=false;paintedRevision=-1;pendingText=null;}
    void reflow(int width,int height,EditBox input){
        ChestLayout next=ChestLayout.fit(width,height,menu.cubeView(),menu.privateView());
        if(!next.equals(layout)){
            layout=next;
            requestedRow=menu.firstSlot()/layout.columns();
            paintedRevision=-1;dragging=false;
        }
        if(input!=null){
            Box search=layout.search();
            input.setX(search.x());input.setY(search.y());input.setWidth(search.width());
        }
    }
    Box main(){return layout.screen(layout.main());}
    Box settingsButton(){return layout.screen(layout.tab(true));}
    Box settingsPanel(){return drawer==Drawer.SETTINGS?layout.screen(layout.drawer(true)):EMPTY;}
    Box search(){return layout.screen(layout.search());}
    Box displayButton(){return layout.screen(layout.tab(false));}
    Box inventory(int i){return i>=0&&i<36?layout.screen(layout.inventory(i)):EMPTY;}
    Box cell(int ordinal){return ordinal>=0&&ordinal<layout.rows()*layout.columns()?layout.screen(layout.cell(ordinal)):EMPTY;}
    Box control(int id){Box box=buttonBox(id);return box==null?EMPTY:layout.screen(box);}
    boolean settingsOpen(){return drawer==Drawer.SETTINGS;}
    List<Box> extra(){
        if(drawer!=Drawer.CLOSED && layout.outsideDrawer())
            return List.of(layout.screen(layout.tab(false)),layout.screen(layout.drawerOutline(settingsOpen())));
        return List.of(layout.screen(layout.tab(false)));
    }
    private int row(){return menu.firstSlot()/layout.columns();}
    private int maxRow(){return StorageGrid.maxRow(menu.resultCount(),layout.columns(),layout.rows());}
    private void scroll(int row){requestedRow=Math.clamp(row,0,maxRow());}
    void searchChanged(String text){
        pendingText=text;textRevision=Math.max(menu.chestSearchRevision(),textRevision)+1;
        requestedRow=0;lastRequest=-20;
    }
    void tick(int ticks){
        if(pendingText!=null){
            if(menu.chestSearchRevision()==textRevision)pendingText=null;
            else{
                if(ticks-lastRequest>=4){screen.send("text",pendingText,textRevision);lastRequest=ticks;}
                return;
            }
        }
        if(requestedRow<0 && !dimensionsMatch())scroll(menu.firstSlot()/layout.columns());
        if(requestedRow<0)return;
        scroll(requestedRow);
        if(dimensionsMatch() && requestedRow==row()){requestedRow=-1;return;}
        if(ticks-lastRequest>=4){
            screen.send("chest_view","",StorageGrid.chestViewport(requestedRow,layout.columns(),layout.rows()));
            lastRequest=ticks;
        }
    }
    private boolean dimensionsMatch(){return menu.visibleColumns()==layout.columns() && menu.visibleRows()==layout.rows();}
    private boolean ready(){return pendingText==null && dimensionsMatch() && (requestedRow<0||row()==requestedRow);}
    private List<Integer> options(){return settingsOpen()?ACTIONS:drawer==Drawer.DISPLAY?PERSONAL:List.of();}
    private boolean available(int id){return id>=200 || !menu.getSlot(id).getItem().isEmpty();}
    private Box buttonBox(int id){
        if(id==SETTINGS)return layout.tab(true);
        if(id==DISPLAY)return layout.tab(false);
        if(id==45)return layout.scrollUp();
        if(id==46)return layout.scrollDown();
        int i=options().indexOf(id);
        return i>=0 && available(id)?layout.option(settingsOpen(),i):null;
    }
    private int hit(int x,int y){
        if(layout.tab(true).contains(x,y))return SETTINGS;
        if(layout.tab(false).contains(x,y))return DISPLAY;
        if(inDrawer(x,y)){
            for(int id:options())if(available(id) && buttonBox(id).contains(x,y))return id;
            return NONE;
        }
        if(layout.search().contains(x,y))return SEARCH;
        if(layout.scrollUp().contains(x,y))return 45;
        if(layout.scrollDown().contains(x,y))return 46;
        if(ready()){
            int ordinal=layout.cellAt(x,y,menu.shownSlots());
            if(ordinal>=0)return StorageGrid.chestSlot(ordinal);
        }
        for(int i=0;i<36;i++)if(layout.inventory(i).contains(x,y))return 54+i;
        return NONE;
    }
    private boolean inDrawer(int x,int y){return drawer!=Drawer.CLOSED && layout.drawerOutline(settingsOpen()).contains(x,y);}
    private boolean personalOn(int id){
        return switch(id){case 200->menu.tooltipsEnabled();case 201->ClientLooks.options.particles;
            case 202->ClientLooks.options.images;default->ClientLooks.options.models;};
    }
    private String label(int id){
        if(id==SETTINGS)return "Settings";
        if(id==DISPLAY)return "Display";
        if(id==SEARCH)return "Search storage";
        if(id>=200)return NAMES.get(id-200);
        return screen.actionLabel(id,menu.getSlot(id).getItem());
    }
    private void say(String text){Minecraft.getInstance().getNarrator().sayNow(Component.literal(text));}
    private void toggle(Drawer next){
        drawer=drawer==next?Drawer.CLOSED:next;dragging=false;
        say(drawer==Drawer.CLOSED?"Drawer closed":drawer==Drawer.SETTINGS?"Chest settings":"Display settings");
    }
    private void activate(int id,int button,boolean shift){
        if(id==SETTINGS && button==0){toggle(Drawer.SETTINGS);return;}
        if(id==DISPLAY && button==0){toggle(Drawer.DISPLAY);return;}
        if(id<0)return;
        if(id>=200){
            if(button!=0)return;
            if(id==200)screen.send("tooltips","",menu.tooltipsEnabled()?0:1);
            else {
                switch(id){case 201->ClientLooks.options.particles=!ClientLooks.options.particles;
                    case 202->ClientLooks.options.images=!ClientLooks.options.images;
                    case 203->ClientLooks.options.models=!ClientLooks.options.models;default->{return;}}
                ClientLooks.saveOptions();
            }
            return;
        }
        if(id==45||id==46){if(button==0)scroll((requestedRow<0?row():requestedRow)+(id==45?-1:1));return;}
        if(button>2 || button<0 || !ready() || menu.revision()!=paintedRevision)return;
        if(menu.controlSlot(id)){
            ControlGlyph.Face face=ControlGlyph.read(menu.getSlot(id).getItem());
            if(face!=null && (face.state()==ControlGlyph.State.DISABLED || face.state()==ControlGlyph.State.READ_ONLY))return;
        }
        screen.press(id);screen.send("slot","",id|(button<<8)|(shift?65536:0));
    }
    boolean click(double mx,double my,int button,boolean shift,EditBox input){
        int x=layout.localX(mx),y=layout.localY(my),id=hit(x,y);
        focus=NONE;screen.focusSearch(id==SEARCH);
        if(id==SEARCH){input.mouseClicked(x,y,button);return true;}
        if(id!=NONE){activate(id,button,shift);return true;}
        if(inDrawer(x,y))return true;
        if(layout.scroll().contains(x,y)&&button==0){dragging=true;drag(my);return true;}
        // Panel chrome owns its clicks as well, including empty/unacknowledged cells.
        return layout.main().contains(x,y);
    }
    private int thumb(){
        int height=layout.scroll().height(),total=Math.max(1,(menu.resultCount()+layout.columns()-1)/layout.columns());
        return Math.max(3,(int)(height*Math.min(1,layout.rows()/(double)total)));
    }
    boolean drag(double my){
        if(!dragging)return false;
        Box b=layout.scroll();
        scroll((int)Math.round((layout.localY(my)-b.y()-thumb()*.5)/Math.max(1,b.height()-thumb())*maxRow()));
        return true;
    }
    boolean release(){boolean previous=dragging;dragging=false;return previous;}
    boolean wheel(double mx,double my,double amount){
        int x=layout.localX(mx),y=layout.localY(my);
        if(inDrawer(x,y))return true;
        if(amount==0 || !(layout.grid().contains(x,y)||layout.scroll().contains(x,y)))return false;
        scroll((requestedRow<0?row():requestedRow)+(amount>0?-1:1));return true;
    }
    boolean key(int key,boolean shift,EditBox input){
        if(key==256 && drawer!=Drawer.CLOSED){
            focus=settingsOpen()?SETTINGS:DISPLAY;drawer=Drawer.CLOSED;screen.focusSearch(false);say("Drawer closed");return true;
        }
        if(key==258){
            List<Integer> order=new ArrayList<>(List.of(SEARCH,SETTINGS));
            if(settingsOpen())for(int id:ACTIONS)if(available(id))order.add(id);
            order.add(DISPLAY);if(drawer==Drawer.DISPLAY)order.addAll(PERSONAL);
            int i=order.indexOf(input.isFocused()?SEARCH:focus);
            focus=order.get(i<0?(shift?order.size()-1:0):Math.floorMod(i+(shift?-1:1),order.size()));
            screen.focusSearch(focus==SEARCH);say(label(focus));return true;
        }
        if(input.isFocused())return false;
        if((key==257||key==335||key==32)&&focus!=NONE){activate(focus,0,shift);return true;}
        if(key==264||key==265){scroll((requestedRow<0?row():requestedRow)+(key==264?1:-1));return true;}
        return false;
    }
    private void enter(GuiGraphics g){
        g.pose().pushPose();g.pose().translate(layout.left(),layout.top(),0);g.pose().scale((float)layout.scale(),(float)layout.scale(),1);
    }
    void background(GuiGraphics g,int[] colors){
        enter(g);
        int w=layout.width(),h=layout.height();
        g.fill(0,0,w,h,0xff17141e);g.fill(1,1,w-1,h-1,colors[1]);g.fill(2,2,w-2,h-2,colors[0]);
        g.fill(3,3,5,19,colors[2]);
        g.pose().popPose();
    }
    void render(GuiGraphics g,Font font,int mx,int my,float partial,EditBox input,int[] colors){
        int x=layout.localX(mx),y=layout.localY(my),hover=hit(x,y);
        enter(g);
        int w=layout.width(),h=layout.height();
        String title=(menu.cubeView()?Content.CUBE:Content.CRATE).get().getName().getString();
        g.drawString(font,font.plainSubstrByWidth(title,w-(screen.cameoVisible()?116:83)),9,8,0xfff5edf5,false);
        screen.chestCameo(g,w-105,5);
        drawButton(g,font,SETTINGS,ControlGlyph.SETTINGS,settingsOpen(),hover);
        drawButton(g,font,DISPLAY,ControlGlyph.IMAGE,drawer==Drawer.DISPLAY,hover);
        input.render(g,x,y,partial);
        for(int i=0;i<layout.columns()*layout.rows();i++){
            Box b=layout.cell(i);int slot=StorageGrid.chestSlot(i);
            if(ready() && i<menu.shownSlots())screen.drawSlot(g,slot,b,hover==slot,"",colors);
            else screen.frame(g,b,0xff22222a,colors[1]);
        }
        if(menu.privateView() || (ready() && menu.resultCount()==0)){
            Box b=layout.grid();
            g.fill(b.x(),b.y(),b.x()+b.width(),b.y()+b.height(),colors[0]);
            g.drawWordWrap(font,Component.literal(menu.privateView()?"Private contents":"No matching items"),
                    b.x()+5,b.y()+7,b.width()-10,colors[2]);
        }
        drawScroll(g,hover,colors);
        String count=menu.privateView()?"Private":menu.filteredView()?menu.resultCount()+" matches":menu.occupiedSlots()+" / "+menu.capacity()+" slots";
        int iy=layout.inventoryY();
        g.drawString(font,"Inventory",9,iy-13,colors[2],false);
        g.drawString(font,count,w-9-font.width(count),iy-13,colors[2],false);
        long remains=menu.transferRemainders();
        for(int i=0;i<36;i++){
            int id=54+i;Box b=layout.inventory(i);
            boolean leftover=(remains&(1L<<menu.getSlot(id).getContainerSlot()))!=0;
            screen.frame(g,b,hover==id?0xff514954:0xff242530,leftover?0xffffc575:colors[1]);
            ItemStack s=menu.getSlot(id).getItem();
            if(!s.isEmpty()){g.renderItem(s,b.x(),b.y());g.renderItemDecorations(font,s,b.x(),b.y());}
        }
        g.drawString(font,font.plainSubstrByWidth("Left: stack   Right: one   Inventory: deposit",w-18),9,h-12,0xffc8bccd,false);
        if(drawer!=Drawer.CLOSED)drawDrawer(g,font,hover,colors);
        g.pose().popPose();
        paintedRevision=menu.revision();
    }
    private void drawButton(GuiGraphics g,Font font,int id,ControlGlyph glyph,boolean selected,int hover){
        Box b=buttonBox(id);
        ControlSprites.button(g,font,glyph,selected?ControlGlyph.State.SELECTED:ControlGlyph.State.NORMAL,
                b.x(),b.y(),b.width(),b.height(),id==DISPLAY?"":label(id),hover==id||focus==id,false);
    }
    private void drawScroll(GuiGraphics g,int hover,int[] colors){
        for(int id:List.of(45,46)){
            Box b=buttonBox(id);g.fill(b.x(),b.y(),b.x()+b.width(),b.y()+b.height(),hover==id?colors[1]:0xff252432);
            int mid=b.x()+b.width()/2;
            for(int j=0;j<3;j++){int yy=b.y()+4+(id==45?j:2-j);g.fill(mid-j,yy,mid+j+1,yy+1,colors[2]);}
        }
        Box b=layout.scroll();int thumb=thumb(),max=maxRow();
        int pos=max==0?0:(int)((b.height()-thumb)*(requestedRow<0?row():requestedRow)/(double)max);
        g.fill(b.x(),b.y(),b.x()+b.width(),b.y()+b.height(),0xff1a1720);
        g.fill(b.x()+1,b.y()+pos,b.x()+b.width()-1,b.y()+pos+thumb,colors[2]);
    }
    private void drawDrawer(GuiGraphics g,Font font,int hover,int[] colors){
        Box b=layout.drawer(settingsOpen());screen.frame(g,b,colors[0],colors[2]);
        g.drawString(font,settingsOpen()?"Transfer":"Display",b.x()+5,b.y()+4,colors[2],false);
        if(settingsOpen())g.drawString(font,"Storage",b.x()+5,b.y()+68,colors[2],false);
        for(int id:options()){
            Box box=buttonBox(id);if(box==null)continue;
            if(id<200)screen.drawSlot(g,id,box,hover==id||focus==id,label(id),colors);
            else ControlSprites.button(g,font,GLYPHS.get(id-200),personalOn(id)?ControlGlyph.State.ON:ControlGlyph.State.OFF,
                    box.x(),box.y(),box.width(),box.height(),label(id),hover==id||focus==id,false);
        }
    }
    void tooltip(GuiGraphics g,Font font,int mx,int my){
        if(!menu.tooltipsEnabled())return;
        int x=layout.localX(mx),y=layout.localY(my),id=hit(x,y);
        if(id==NONE||id==SEARCH)return;
        if(id==SETTINGS||id==DISPLAY||id>=200){
            String hint=id==SETTINGS?"Settings: transfer and storage actions":id==DISPLAY?"Display settings":label(id)+(personalOn(id)?" on":" off");
            g.renderTooltip(font,Component.literal(hint),mx,my);return;
        }
        ItemStack s=menu.getSlot(id).getItem();
        if(!s.isEmpty()){
            if(menu.controlSlot(id))screen.controlTooltip(g,s,mx,my);
            else g.renderTooltip(font,s,mx,my);
        }else{
            int i=layout.cellAt(x,y,menu.shownSlots());
            if(i>=0)g.renderTooltip(font,Component.literal("Empty slot "+(menu.firstSlot()+i+1)),mx,my);
        }
    }
}
