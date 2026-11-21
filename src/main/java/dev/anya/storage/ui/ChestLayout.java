package dev.anya.storage.ui;

import dev.anya.storage.ui.StorageGrid.Box;

/** Drawing, hit testing and recipe-browser bounds use this one geometry in scaled GUI coordinates. */
public record ChestLayout(int columns,int rows,int width,int height,double left,double top,double scale,
                          boolean outsideDrawer) {
    private static final int GRID_X=9, GRID_Y=42, TAB_WIDTH=22, DRAWER_WIDTH=100;
    public static ChestLayout fit(int screenWidth,int screenHeight,boolean cube,boolean privateView) {
        return fit(screenWidth,screenHeight,cube,privateView,0);
    }
    public static ChestLayout fit(int screenWidth,int screenHeight,boolean cube,boolean privateView,int bottomSpace) {
        int usableHeight=Math.max(1,screenHeight-Math.clamp(bottomSpace,0,screenHeight));
        int columns=cube && screenWidth>=370 ? StorageGrid.WIDE_COLUMNS : 9;
        int maximum=privateView?3:cube?StorageGrid.CHEST_ROWS:6;
        int rows=Math.clamp((usableHeight-151)/18,2,maximum);
        int width=columns*18+32, height=143+rows*18;
        double scale=Math.min(1,Math.min(Math.max(1,screenWidth-4)/(double)(width+2*TAB_WIDTH),
                Math.max(1,usableHeight-6)/(double)height));
        double left=(screenWidth-width*scale)/2,top=(usableHeight-height*scale)/2;
        return new ChestLayout(columns,rows,width,height,left,top,scale,
                left>=(TAB_WIDTH+DRAWER_WIDTH+4)*scale);
    }
    public int localX(double x){return (int)Math.floor((x-left)/scale);}
    public int localY(double y){return (int)Math.floor((y-top)/scale);}
    public static Box bounds(double x,double y,double width,double height){
        int left=(int)Math.floor(x),top=(int)Math.floor(y);
        return new Box(left,top,(int)Math.ceil(x+width)-left,(int)Math.ceil(y+height)-top);
    }
    public Box screen(Box box){return bounds(left+box.x()*scale,top+box.y()*scale,box.width()*scale,box.height()*scale);}
    public Box main(){return new Box(0,0,width,height);}
    public Box search(){return new Box(GRID_X,22,width-18,15);}
    public Box grid(){return new Box(GRID_X-1,GRID_Y-1,columns*18,rows*18);}
    public Box cell(int ordinal){return new Box(GRID_X+ordinal%columns*18,GRID_Y+ordinal/columns*18,16,16);}
    public int cellAt(int x,int y,int shown){
        if(x<GRID_X || y<GRID_Y || !grid().contains(x,y))return -1;
        int i=(y-GRID_Y)/18*columns+(x-GRID_X)/18;
        return i<shown && cell(i).contains(x,y)?i:-1;
    }
    public int inventoryY(){return GRID_Y+rows*18+18;}
    public Box inventory(int i){return new Box((width-9*18)/2+1+i%9*18,inventoryY()+i/9*18+(i>=27?4:0),16,16);}
    public Box scrollUp(){return new Box(width-17,GRID_Y,11,12);}
    public Box scrollDown(){return new Box(width-17,GRID_Y+rows*18-14,11,12);}
    public Box scroll(){return new Box(width-15,GRID_Y+14,7,rows*18-30);}
    public Box tab(boolean actions){return actions?new Box(width-80,4,74,16):new Box(-TAB_WIDTH,42,TAB_WIDTH,23);}
    public Box drawer(boolean actions){
        int x=outsideDrawer?(actions?width:-TAB_WIDTH-DRAWER_WIDTH):(actions?width-DRAWER_WIDTH-3:3);
        return new Box(x,Math.min(42,height-(actions?151:99)-3),DRAWER_WIDTH,actions?151:99);
    }
    public Box drawerOutline(boolean actions){
        Box b=drawer(actions);
        return new Box(b.x()-1,b.y()-1,b.width()+2,b.height()+2);
    }
    public Box option(boolean actions,int index){
        Box panel=drawer(actions);
        int headingGap=actions&&index>=3?11:0;
        return new Box(panel.x()+4,panel.y()+15+index*17+headingGap,panel.width()-8,16);
    }
}
