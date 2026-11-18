package dev.anya.storage.ui;

import dev.anya.storage.AnyaStorage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** One tiny nearest-neighbour atlas. Controls and guide widgets use the same ink and state marks. */
public final class ControlSprites {
    private static final ResourceLocation ATLAS=ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"textures/gui/control_atlas.png");
    private static final int INK=0xff191626, LILAC=0xffad9abb, MINT=0xffa5e9dc, ROSE=0xffedaac5;

    public static void sprite(GuiGraphics g,ControlGlyph glyph,int x,int y){
        int cell=glyph.ordinal();
        g.blit(ATLAS,x,y,cell%16*16,cell/16*16,16,16,256,256);
    }

    private static void shell(GuiGraphics g,int x,int y,int w,int h,int color){
        g.fill(x+2,y,x+w-2,y+h,color);
        g.fill(x+1,y+1,x+w-1,y+h-1,color);
        g.fill(x,y+2,x+w,y+h-2,color);
    }

    public static void button(GuiGraphics g,Font font,ControlGlyph glyph,ControlGlyph.State state,
                              int x,int y,int w,int h,String label,boolean hover,boolean pressed){
        boolean disabled=state==ControlGlyph.State.DISABLED;
        boolean selected=state==ControlGlyph.State.SELECTED;
        int edge=disabled?0xff655a71:pressed?ROSE:hover?0xffe1c9ee:selected?MINT:0xff71607d;
        shell(g,x,y,w,h,edge);
        shell(g,x+1,y+1,w-2,h-2,disabled?0xff2b2733:pressed?0xff514052:hover?0xff494052:selected?0xff393747:0xff302939);
        int dy=pressed&&!disabled?1:0;
        int iconX=label.isEmpty()?x+(w-16)/2:x+2;
        int iconY=y+(h-16)/2+dy;
        sprite(g,glyph,iconX,iconY);
        if(disabled)g.fill(iconX,iconY,iconX+16,iconY+16,0x80413849);
        if(!label.isEmpty()){
            boolean hasMark=state==ControlGlyph.State.ON||state==ControlGlyph.State.OFF||selected;
            int available=w-22-(hasMark?7:0);
            g.drawString(font,font.plainSubstrByWidth(label,Math.max(1,available)),x+20,y+(h-8)/2+dy,
                disabled?0xffb2a6b8:0xfff3eaf4,false);
        }
        if(state==ControlGlyph.State.ON||state==ControlGlyph.State.OFF||selected){
            int r=x+w-1,b=label.isEmpty()?y+h-1:y+h/2+3;
            g.fill(r-7,b-6,r,b,INK);
            if(state==ControlGlyph.State.OFF){
                g.fill(r-6,b-4,r-2,b-3,LILAC);
            }else{
                g.fill(r-6,b-4,r-5,b-2,MINT);
                g.fill(r-5,b-3,r-4,b-1,MINT);
                g.fill(r-4,b-4,r-3,b-2,MINT);
                g.fill(r-3,b-5,r-2,b-3,MINT);
                g.fill(r-2,b-6,r-1,b-4,MINT);
            }
        }
        if(hover&&!disabled)g.fill(x+3,y+h-2,x+Math.min(w-3,10),y+h-1,pressed?ROSE:LILAC);
    }
    private ControlSprites(){}
}
