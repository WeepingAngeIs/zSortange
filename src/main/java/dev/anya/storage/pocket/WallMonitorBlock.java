package dev.anya.storage.pocket;

import dev.anya.storage.*;
import java.util.HashMap;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.*;

/** A painted surface occupies one physical anchor. Expansion never writes to adjoining blocks. */
final class WallMonitorBlock extends CameraDevices.DeviceBlock {
    final int maxW,maxH;
    WallMonitorBlock(int w,int h){maxW=w;maxH=h;}
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx){
        var face=ctx.getClickedFace();if(!face.getAxis().isHorizontal())return null;
        var level=ctx.getLevel();var cell=ctx.getClickedPos();var support=cell.relative(face.getOpposite());
        if(!level.getBlockState(support).isFaceSturdy(level,support,face))return null;
        for(var d:WallPanel.at(level,cell))if(!d.isRemoved()&&WallPanel.face(d)==face&&d.panel.covers(d,cell))return null;
        return defaultBlockState().setValue(BlockStateProperties.FACING,face);
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext context){
        return switch(s.getValue(BlockStateProperties.FACING)){
            case NORTH -> Block.box(0,0,15,16,16,16);
            case SOUTH -> Block.box(0,0,0,16,16,1);
            case EAST -> Block.box(0,0,0,1,16,16);
            case WEST -> Block.box(15,0,0,16,16,16);
            case UP -> Block.box(0,0,0,16,1,16);
            case DOWN -> Block.box(0,15,0,16,16,16);
        };
    }
    static boolean shellFace(PocketLedger.Pocket room,BlockPos pos,Direction f){
        int x=pos.getX()-room.x(),z=pos.getZ()-room.z();
        return pos.getY()>=65&&pos.getY()<=96&&x>=0&&x<32&&z>=0&&z<32&&!(x>=14&&x<=18&&z>=19&&pos.getY()<=72)
                &&(x==0&&f==Direction.EAST||x==31&&f==Direction.WEST||z==0&&f==Direction.SOUTH||z==31&&f==Direction.NORTH);
    }
    private static final java.util.Map<ServerPlayer,Long> lastFit=new java.util.WeakHashMap<>();
    static String resize(ServerPlayer p,CameraDevices.Device d,int w,int h,boolean fit){
        var tier=(WallMonitorBlock)d.getBlockState().getBlock();
        if(w<1||w>tier.maxW||h<1||h>tier.maxH)return "That size exceeds this monitor tier.";
        var level=p.serverLevel();var state=d.getBlockState();var anchor=d.getBlockPos();var f=WallPanel.face(d);
        var room=PocketService.inPocket(level)?PocketService.lease(p.getUUID()):null;
        var r=room==null?null:PocketService.loadedPocket(p.getServer(),room.pocket());
        if(PocketService.inPocket(level)&&(r==null||!PocketService.visitingOwner(p,r.id())||!RoomEditor.active(p)))
            return "Visit your Skygrid with room editing enabled.";
        if(!Safety.buildReason(p,level,anchor,state,false).isEmpty())return "You cannot resize this monitor here.";
        if(fit){
            long now=p.getServer().getTickCount();var previous=lastFit.get(p);
            if(previous!=null&&now-previous<10)return "Wait a moment before fitting this wall again.";
            lastFit.put(p,now);
        }
        var checked=new HashMap<BlockPos,String>();
        java.util.function.BiFunction<Integer,Integer,String> problem=(x,y)->{
            var cell=WallPanel.cell(d,x,y);
            return checked.computeIfAbsent(cell,c->{
                var back=c.relative(f.getOpposite());
                if(!level.hasChunkAt(c)||!level.hasChunkAt(back))return "Part of the wall is unloaded.";
                if(r!=null&&!shellFace(r,c,f)&&!RoomBounds.decoration(c.getX()-r.x(),c.getY(),c.getZ()-r.z()))
                    return "Keep the exit and room shell clear.";
                if(!level.getBlockState(back).isFaceSturdy(level,back,f))return "The screen needs a solid wall behind it.";
                if(!c.equals(anchor)&&!level.isEmptyBlock(c))return "Clear the space in front of the wall first.";
                if(!Safety.buildReason(p,level,c,state,false).isEmpty()||!Safety.locationReason(p,level,back).isEmpty())
                    return "You cannot use that part of the wall.";
                for(var other:WallPanel.at(level,c))if(other!=d&&!other.isRemoved()&&other.panel.covers(other,c))
                    return "Another screen already uses this space.";
                return "";
            });
        };
        WallPanel size;
        if(fit){
            String center=problem.apply(0,0);if(!center.isEmpty())return center;
            int l=0,b=0,right=0,t=0;
            boolean[] more={true,true,true,true};
            for(int pass=0;pass<Math.max(tier.maxW,tier.maxH);pass++){
                boolean changed=false;
                for(int side=0;side<4;side++){
                    if(!more[side]||(side<2?right+l+1>=tier.maxW:t+b+1>=tier.maxH))continue;
                    boolean ok=true;
                    if(side<2){int x=side==0?-l-1:right+1;for(int y=-b;y<=t&&ok;y++)ok=problem.apply(x,y).isEmpty();}
                    else{int y=side==2?-b-1:t+1;for(int x=-l;x<=right&&ok;x++)ok=problem.apply(x,y).isEmpty();}
                    if(!ok){more[side]=false;continue;}
                    switch(side){case 0->l++;case 1->right++;case 2->b++;case 3->t++;}
                    changed=true;
                }
                if(!changed)break;
            }
            size=new WallPanel(l+right+1,b+t+1,l,b);
        }else{
            int l=w==d.panel.width()?d.panel.left():(w-1)/2,b=h==d.panel.height()?d.panel.below():(h-1)/2;
            size=new WallPanel(w,h,l,b);
            for(int x=-l;x<w-l;x++)for(int y=-b;y<h-b;y++){
                var deny=problem.apply(x,y);if(!deny.isEmpty())return deny;
            }
        }
        if(level.getBlockEntity(anchor)!=d||state!=d.getBlockState()||!d.owned(p))return "The monitor changed. Open it again.";
        d.panel(size);d.setChanged();level.sendBlockUpdated(anchor,state,state,Block.UPDATE_CLIENTS);
        return "";
    }
}
