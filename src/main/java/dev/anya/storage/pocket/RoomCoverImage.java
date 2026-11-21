package dev.anya.storage.pocket;

import java.util.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Bake tiny public block models once per distinct state per redraw, then composite by depth. */
final class RoomCoverImage {
    private static final int N=256, W=RoomCover.WIDTH, H=RoomCover.HEIGHT;
    private static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath("anyastorage","dynamic/room_cover");
    private static final int[][] strips=new int[W][];
    private static final long[] seen=new long[W];
    private static DynamicTexture texture;
    private static final boolean[] visible=new boolean[W];
    private static boolean dirty;
    private static long last;
    static void accept(RoomCover.Strip strip,long tick){
        int x=strip.index();if(x<0 || x>=W)return;
        if(!strip.unchanged()){strips[x]=strip.entries();dirty=true;}
        if(!visible[x] && strips[x]!=null && strips[x].length>0)dirty=true;
        visible[x]=true;
        seen[x]=tick;
    }
    static void clear(){
        Arrays.fill(strips,null);Arrays.fill(seen,0);Arrays.fill(visible,false);dirty=false;last=0;
        if(texture!=null){Minecraft.getInstance().getTextureManager().release(ID);texture=null;}
    }
    static ResourceLocation surface(long tick){
        for(int x=0;x<W;x++)if(visible[x] && tick-seen[x]>40){visible[x]=false;dirty=true;}
        if(!dirty || tick-last<2)return texture==null?null:ID;
        dirty=false;last=tick;
        if(texture==null){
            texture=new DynamicTexture(N,N,true);Minecraft.getInstance().getTextureManager().register(ID,texture);
        }
        var pixels=texture.getPixels();if(pixels==null)return null;
        pixels.fillRect(0,0,N,N,0);
        var depths=new float[N*N];Arrays.fill(depths,Float.NEGATIVE_INFINITY);
        Map<Integer,Stamp> stamps=new HashMap<>();
        for(int x=0;x<W;x++)if(strips[x]!=null && tick-seen[x]<=40)for(int packed:strips[x]){
            int index=packed&255;if(index>=RoomCover.CELLS)continue;
            int z=index/H+2,y=index%H,rx=x+2;
            var stamp=stamps.computeIfAbsent(packed>>>8,id->new Stamp(Block.stateById(id)));
            int left=56+6*rx-2*z-3,top=96+rx+3*z-6*y-7;
            for(int j=0;j<Stamp.SIZE;j++)for(int i=0;i<Stamp.SIZE;i++){
                int k=j*Stamp.SIZE+i,px=left+i,py=top+j;
                if(stamp.color[k]==0 || px<0 || py<0 || px>=N || py>=N)continue;
                float d=3*rx+9*z+5*y+stamp.depth[k];int target=py*N+px;
                if(d>depths[target]){depths[target]=d;pixels.setPixelRGBA(px,py,stamp.color[k]);}
            }
        }
        texture.upload();return ID;
    }
    private record Point(float x,float y,float depth,float u,float v){}
    private static final class Stamp {
        static final int SIZE=13;
        final int[] color=new int[SIZE*SIZE];
        final float[] depth=new float[SIZE*SIZE];
        Stamp(BlockState state){
            Arrays.fill(depth,Float.NEGATIVE_INFINITY);
            var mc=Minecraft.getInstance();var model=mc.getBlockRenderer().getBlockModel(state);
            var random=RandomSource.create(42);
            for(Direction side:Arrays.asList(null,Direction.UP,Direction.SOUTH,Direction.EAST)){
                random.setSeed(42);
                for(var quad:model.getQuads(state,side,random)){
                    int[] data=quad.getVertices();int stride=data.length/4;
                    var sprite=quad.getSprite();Point[] p=new Point[4];
                    for(int n=0;n<4;n++){
                        int at=n*stride;
                        float x=Float.intBitsToFloat(data[at]),y=Float.intBitsToFloat(data[at+1]),z=Float.intBitsToFloat(data[at+2]);
                        p[n]=new Point(3+6*x-2*z,7+x+3*z-6*y,3*x+5*y+9*z,
                                (Float.intBitsToFloat(data[at+4])-sprite.getU0())/(sprite.getU1()-sprite.getU0()),
                                (Float.intBitsToFloat(data[at+5])-sprite.getV0())/(sprite.getV1()-sprite.getV0()));
                    }
                    int tint=quad.isTinted()?mc.getBlockColors().getColor(state,null,null,quad.getTintIndex()):0xffffff;
                    triangle(quad,p[0],p[1],p[2],tint);triangle(quad,p[0],p[2],p[3],tint);
                }
            }
        }
        private void triangle(BakedQuad q,Point a,Point b,Point c,int tint){
            float det=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);
            if(Math.abs(det)<.0001f)return;
            var sprite=q.getSprite();int w=sprite.contents().width(),h=sprite.contents().height();
            float shade=switch(q.getDirection()){case UP -> 1;case EAST,WEST -> .76f;default -> .87f;};
            for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
                float dx=x+.5f-c.x,dy=y+.5f-c.y;
                float k=((b.y-c.y)*dx+(c.x-b.x)*dy)/det,l=((c.y-a.y)*dx+(a.x-c.x)*dy)/det,m=1-k-l;
                if(k<0 || l<0 || m<0)continue;
                int i=y*SIZE+x;float d=k*a.depth+l*b.depth+m*c.depth;if(d<=depth[i])continue;
                int rgba=sprite.getPixelRGBA(0,Math.clamp((int)((k*a.u+l*b.u+m*c.u)*w),0,w-1),Math.clamp((int)((k*a.v+l*b.v+m*c.v)*h),0,h-1));
                if((rgba>>>24)<16)continue;
                int red=(int)((rgba&255)*((tint>>16)&255)*shade/255);
                int green=(int)(((rgba>>8)&255)*((tint>>8)&255)*shade/255);
                int blue=(int)(((rgba>>16)&255)*(tint&255)*shade/255);
                color[i]=0xff000000|blue<<16|green<<8|red;depth[i]=d;
            }
        }
    }
}
