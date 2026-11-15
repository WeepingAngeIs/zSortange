package dev.anya.storage.ui;

/** Shared, engine-independent viewport arithmetic. Network indices always refer to server snapshots. */
public final class StorageGrid {
    public static final int COLUMNS=9,MAX_ROWS=5,CELLS=45;
    public static int rows(int count){return count<=0?0:1+(count-1)/COLUMNS;}
    public static int clampRows(int rows){return Math.max(1,Math.min(MAX_ROWS,rows));}
    public static int maxStartRow(int count,int visibleRows){return Math.max(0,rows(count)-clampRows(visibleRows));}
    public static int start(int count,int row,int visibleRows){return Math.max(0,Math.min(maxStartRow(count,visibleRows),row))*COLUMNS;}
    public static int visible(int count,int first,int visibleRows){return Math.max(0,Math.min(clampRows(visibleRows)*COLUMNS,count-first));}
    public static int absolute(int count,int first,int visibleRows,int displayIndex){
        if(first<0||first%COLUMNS!=0||displayIndex<0||displayIndex>=visible(count,first,visibleRows))return -1;
        return first+displayIndex;
    }
    public record Box(int x,int y,int width,int height){
        public boolean contains(double px,double py){return px>=x&&py>=y&&px<x+width&&py<y+height;}
    }
    public record Layout(int rows,int height,double scale,double left,double top){
        public static final int WIDTH=382,GRID_X=104,GRID_Y=50,CONTROLS_X=290;
        public double x(double screenX){return (screenX-left)/scale;}
        public double y(double screenY){return (screenY-top)/scale;}
        public int inventoryY(){return GRID_Y+rows*18+27;}
        public Box storage(int index){return new Box(GRID_X+(index%9)*18,GRID_Y+(index/9)*18,16,16);}
        public Box inventory(int index){return new Box(GRID_X+(index%9)*18,inventoryY()+(index/9)*18+(index>=27?4:0),16,16);}
        public Box control(int index){return new Box(CONTROLS_X,44+index*21,80,19);}
        public Box scroll(){return new Box(270,GRID_Y,8,rows*18-2);}
        public int storageAt(double x,double y,int shown){for(int i=0;i<shown;i++)if(storage(i).contains(x,y))return i;return -1;}
        public int inventoryAt(double x,double y){for(int i=0;i<36;i++)if(inventory(i).contains(x,y))return i;return -1;}
    }
    public static Layout layout(int width,int height,int count,boolean storage){
        // At small GUI dimensions the complete panel scales, not Minecraft's global GUI setting.
        int preferred=height>=266?5:height>=230?4:3;
        int shownRows=storage?Math.max(1,Math.min(preferred,rows(count))):5;
        int panelHeight=Math.max(244,50+shownRows*18+27+76+25);
        double scale=Math.min(1,Math.min(Math.max(1,width-8)/(double)Layout.WIDTH,Math.max(1,height-8)/(double)panelHeight));
        return new Layout(shownRows,panelHeight,scale,(width-Layout.WIDTH*scale)/2,(height-panelHeight*scale)/2);
    }
    private StorageGrid(){}
}
