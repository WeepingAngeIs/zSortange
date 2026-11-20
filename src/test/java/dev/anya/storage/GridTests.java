package dev.anya.storage;
import dev.anya.storage.ui.StorageGrid;
public final class GridTests {
    private static int checked;
    private static void check(boolean condition,String message){checked++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        int[] capacities={0,1,8,9,17,27,45,63,81,108,144,216,270,324,378,432,16384};
        int[][] sizes={{320,180},{426,240},{640,360},{854,480},{1280,720},{1920,1080},{2560,1440}};
        for(int capacity:capacities)for(int visibleRows=1;visibleRows<=5;visibleRows++){
            int max=StorageGrid.maxStartRow(capacity,visibleRows);
            for(int row:new int[]{-9,0,1,max/2,max,max+1,Integer.MAX_VALUE}){
                int first=StorageGrid.start(capacity,row,visibleRows),shown=StorageGrid.visible(capacity,first,visibleRows);
                check(first>=0&&first%9==0,"row alignment");check(shown>=0&&shown<=45,"window budget");
                for(int cell=-1;cell<=45;cell++){
                    int real=StorageGrid.absolute(capacity,first,visibleRows,cell);
                    check(real==-1||real>=0&&real<capacity,"no phantom slots");
                    check((real>=0)==(cell>=0&&cell<shown),"exact hit map");
                }
                if(capacity>0&&row>=max)check(first+shown==capacity,"last real slot always reachable");
            }
            for(int[] size:sizes){
                var layout=StorageGrid.layout(size[0],size[1],capacity,true);
                check(layout.scale()>0&&layout.left()>=0&&layout.top()>=0,"panel fits GUI dimensions");
                int shown=StorageGrid.visible(capacity,0,layout.rows());
                for(int i=0;i<shown;i++){
                    var box=layout.storage(i);double sx=layout.left()+(box.x()+8)*layout.scale(),sy=layout.top()+(box.y()+8)*layout.scale();
                    check(layout.storageAt(layout.x(sx),layout.y(sy),shown)==i,"scaled pointer maps to displayed slot");
                    check(layout.storageAt(box.x()+16,box.y()+8,shown)==-1,"slot gutters are not hit targets");
                    for(int c=0;c<9;c++)check(!layout.control(c).contains(box.x()+8,box.y()+8),"controls cannot occupy storage");
                }
                for(int i=0;i<36;i++){var b=layout.inventory(i);check(layout.inventoryAt(b.x()+8,b.y()+8)==i,"inventory/hotbar index identity");}
            }
        }
        check(StorageGrid.start(216,Integer.MAX_VALUE,3)==189,"malicious huge row clamps safely");
        System.out.println("GRID_LAYOUT_ASSERTIONS="+checked+" (arithmetic and hitboxes, not network clients)");
    }
}
