package dev.anya.storage.guide;

import dev.anya.storage.*;
import dev.anya.storage.appearance.ClientLooks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.*;
import java.util.*;

/** Responsive illustrated codex. Body text scrolls; no text is shrunk to squeeze in an entire chapter. */
public final class GuideScreen extends Screen {
    private final List<GuidePage> pages;private final long revision;
    private int page,scroll,indexOffset,left,top,bookW,bookH,rightX,textW,bodyY,bodyH;
    private boolean index;
    private final List<Button> indexButtons=new ArrayList<>();
    public GuideScreen(GuidePacket packet){super(Component.literal("Ancient Technology"));pages=packet.pages();revision=packet.revision();}
    public void setPage(int number){page=Math.max(0,Math.min(pages.size()-1,number));scroll=0;index=false;rebuildWidgets();}
    @Override protected void init(){
        bookW=Math.min(460,width-16);bookH=Math.min(286,height-16);left=(width-bookW)/2;top=(height-bookH)/2;
        rightX=left+bookW/2+13;textW=left+bookW-23-rightX;bodyY=top+67;bodyH=bookH-111;
        int y=top+bookH-28;
        addRenderableWidget(Button.builder(Component.literal("<"),b->{if(index){indexOffset=Math.max(0,indexOffset-indexCapacity());rebuildWidgets();}else setPage(page-1);}).bounds(left+18,y,25,18).build());
        addRenderableWidget(Button.builder(Component.literal(">"),b->{if(index){indexOffset=Math.min(Math.max(0,pages.size()-indexCapacity()),indexOffset+indexCapacity());rebuildWidgets();}else setPage(page+1);}).bounds(left+bookW-43,y,25,18).build());
        addRenderableWidget(Button.builder(Component.literal(index?"Read":"Contents"),b->{index=!index;rebuildWidgets();}).bounds(left+48,y,64,18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"),b->onClose()).bounds(left+bookW-96,y,48,18).build());
        indexButtons.clear();
        if(index){
            for(int i=0;i<indexCapacity()&&i+indexOffset<pages.size();i++){
                int target=i+indexOffset;GuidePage entry=pages.get(target);
                String label=(target+1)+". "+entry.title();
                var button=Button.builder(Component.literal(font.plainSubstrByWidth(label,textW-6)),b->setPage(target)).bounds(rightX,bodyY+i*18,textW,17).build();
                button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(entry.chapter()+": "+entry.title())));addRenderableWidget(button);indexButtons.add(button);
            }
        }
    }
    private int indexCapacity(){return Math.max(4,bodyH/18);}
    private List<FormattedCharSequence> lines(){return font.split(Component.literal(pages.get(page).body()),textW);}
    @Override public boolean mouseScrolled(double mx,double my,double dx,double dy){
        if(index){indexOffset=Math.max(0,Math.min(Math.max(0,pages.size()-indexCapacity()),indexOffset-(int)Math.signum(dy)*3));rebuildWidgets();}
        else scroll=Math.max(0,Math.min(Math.max(0,lines().size()-bodyH/11),scroll-(int)Math.signum(dy)*3));
        return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==266){setPage(page-1);return true;}if(key==267){setPage(page+1);return true;}
        if(key==268){index=true;indexOffset=0;rebuildWidgets();return true;}
        if(key==264||key==265){mouseScrolled(0,0,0,key==264?-1:1);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean isPauseScreen(){return false;}
    private ItemStack art(String key){
        if(!key.startsWith("item:"))return ClientLooks.shell(key);
        ResourceLocation id=ResourceLocation.tryParse(key.substring(5));return id==null?ItemStack.EMPTY:new ItemStack(BuiltInRegistries.ITEM.get(id));
    }
    // Screen.render calls renderBackground before widgets; our artwork must not be blurred a second time.
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float partial){}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.renderBackground(g,mx,my,partial);
        g.fill(0,0,width,height,0xc5111524);
        g.pose().pushPose();g.pose().translate(left,top,0);g.pose().scale(bookW/460f,bookH/286f,1);
        g.blit(ExpansionPackets.id("textures/gui/ancient_technology.png"),0,0,0,0,460,286,460,286);g.pose().popPose();
        g.drawCenteredString(font,title,left+bookW/2,top+12,0xfff4dec0);
        GuidePage p=pages.get(page);int leftW=bookW/2-40;
        g.drawString(font,font.plainSubstrByWidth(index?"FIELD INDEX":p.chapter().toUpperCase(Locale.ROOT),leftW),left+25,top+36,0xff806359,false);
        float scale=Math.max(2.0f,Math.min(5.8f,Math.min(leftW/28f,(bookH-165)/16f)));
        g.pose().pushPose();g.pose().translate(left+bookW/4-8*scale,top+53,0);g.pose().scale(scale,scale,1);g.renderItem(art(index?"bow_cat":p.art()),0,0);g.pose().popPose();
        int recipeY=top+bookH-104;
        if(!index&&(p.note().startsWith("UNAVAILABLE")||p.note().startsWith("RECIPE UNAVAILABLE")))g.drawString(font,"RECIPE UNAVAILABLE",left+25,top+49,0xffa04450,false);
        if(!index&&!p.cells().isEmpty()){
            int gridX=left+bookW/4-27;
            for(int i=0;i<9;i++){
                int x=gridX+(i%3)*18,y=recipeY+(i/3)*18;g.fill(x-1,y-1,x+17,y+17,0xffbaa791);g.fill(x,y,x+16,y+16,0xffe0d0b7);
                if(i<p.cells().size()){
                    GuidePage.Cell cell=p.cells().get(i);if(!cell.id().isBlank()){ResourceLocation id=ResourceLocation.tryParse(cell.id());if(id!=null)g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(id)),x,y);}
                }
            }
            g.drawCenteredString(font,"CRAFTING GRID",left+bookW/4,recipeY-12,0xff765452);
        }else{
            String label=index?"ELEMENT / SKIN / TIER":"MAKE IT YOURS";g.drawCenteredString(font,label,left+bookW/4,recipeY-9,0xff765452);
            List<FormattedCharSequence> note=font.split(Component.literal(index?"Use Next to browse the index. Choose a title to open its page.":p.note()),leftW);
            for(int i=0;i<Math.min(5,note.size());i++)g.drawString(font,note.get(i),left+25,recipeY+7+i*10,0xff725f57,false);
        }
        String heading=index?"Contents":p.title();List<FormattedCharSequence> titleLines=font.split(Component.literal(heading),textW);
        for(int i=0;i<Math.min(2,titleLines.size());i++)g.drawString(font,titleLines.get(i),rightX,top+36+i*11,0xff40313c,false);
        if(!index){
            List<FormattedCharSequence> body=lines();int visible=bodyH/11;scroll=Math.max(0,Math.min(scroll,Math.max(0,body.size()-visible)));
            for(int i=0;i<visible&&scroll+i<body.size();i++)g.drawString(font,body.get(scroll+i),rightX,bodyY+i*11,0xff463d3d,false);
            if(body.size()>visible){int x=left+bookW-16;g.fill(x,bodyY,x+2,bodyY+bodyH,0xffd2bda1);int thumb=Math.max(10,bodyH*visible/body.size()),position=(bodyH-thumb)*scroll/Math.max(1,body.size()-visible);g.fill(x,bodyY+position,x+2,bodyY+position+thumb,0xff846073);}
        }
        g.drawCenteredString(font,(index?"INDEX "+(indexOffset+1):"PAGE "+(page+1))+" / "+pages.size(),left+bookW/2,top+bookH-22,0xffe6cdb2);
        super.render(g,mx,my,partial);
        if(!index){
            for(int i=0;i<p.cells().size();i++){int x=left+bookW/4-27+(i%3)*18,y=recipeY+(i/3)*18;var cell=p.cells().get(i);if(!cell.id().isBlank()&&mx>=x&&mx<x+16&&my>=y&&my<y+16)g.renderTooltip(font,Component.literal(cell.label()+" | "+cell.id()),mx,my);}
            if(mx>left+20&&mx<left+bookW/2-10&&my>recipeY-14&&my<recipeY-2&&!p.recipe().isBlank())g.renderTooltip(font,Component.literal(p.recipe()+". "+p.note()),mx,my);
        }
    }
}
