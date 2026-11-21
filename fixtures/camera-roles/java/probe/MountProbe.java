package probe;

import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraFeed;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

final class MountProbe {
    static void sixFaces(ServerPlayer actor)throws Exception{
        var level=actor.serverLevel();
        BlockPos support=new BlockPos(-10,84,8);
        level.setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        var capture=CameraFeed.class.getDeclaredMethod("capture",ServerPlayer.class,ServerLevel.class,CameraDevices.Device.class,boolean.class);
        capture.setAccessible(true);
        for(Direction face:Direction.values()){
            var camera=support.relative(face);
            var forward=Vec3.atLowerCornerOf(face.getNormal());
            var center=Vec3.atCenterOf(camera);
            var stack=new ItemStack(CameraDevices.CAMERA.get().asItem());
            actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
            var hit=new BlockHitResult(Vec3.atCenterOf(support).add(forward.scale(.5)),face,support,false);
            actor.gameMode.useItemOn(actor,level,stack,InteractionHand.MAIN_HAND,hit);
            var state=level.getBlockState(camera);
            Orders.check(state.is(CameraDevices.CAMERA.get()) && state.getValue(BlockStateProperties.FACING)==face
                    && stack.isEmpty(),"Survival camera attachment "+face);
            var be=(CameraDevices.Device)level.getBlockEntity(camera);
            Orders.check(be.owned(actor),"Placed ownership "+face);
            var right=face.getAxis().isVertical()?new Vec3(1,0,0):Vec3.atLowerCornerOf(face.getClockWise().getNormal());
            var up=forward.cross(right);
            var corner=center.add(right.scale(.42)).add(up.scale(.42));
            for(var shape:java.util.List.of(state.getShape(level,camera),state.getCollisionShape(level,camera,CollisionContext.empty()))){
                var lens=shape.clip(center.add(forward.scale(2)),center,camera);
                Orders.check(lens!=null && lens.getDirection()==face && lens.getLocation().distanceTo(center.add(forward.scale(.5)))<.001,
                        "Lens selection/collision "+face);
                Orders.check(shape.clip(corner.add(forward.scale(2)),corner.subtract(forward.scale(2)),camera)==null,"Cube corner hit "+face);
            }
            var legacy=NbtUtils.writeBlockState(state);
            legacy.getCompound("Properties").remove("pan");
            var decoded=NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(Registries.BLOCK),legacy);
            Orders.check(decoded.equals(state),"Pre-swivel facing failed to decode "+face);
            var target=camera.relative(face);
            level.setBlockAndUpdate(target,Blocks.EMERALD_BLOCK.defaultBlockState());
            int[] frame=(int[])capture.invoke(null,actor,level,be,face==Direction.UP);
            Orders.check(frame[136]==Blocks.EMERALD_BLOCK.defaultBlockState().getMapColor(level,target).col,"Lens samples wrong face "+face);
            level.removeBlock(target,false);level.removeBlock(camera,false);
        }
        level.removeBlock(support,false);
    }
}
