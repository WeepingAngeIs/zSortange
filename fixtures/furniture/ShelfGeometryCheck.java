package dev.anya.storage.furniture;

import dev.anya.storage.Content;
import dev.anya.storage.StorageBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.Vec3;

/** Physical input rays around the glass shell, independent of furniture orientation. */
final class ShelfGeometryCheck {
    static void run() {
        for (var facing:Direction.Plane.HORIZONTAL) {
            var outline=Content.SHELF.get().defaultBlockState().setValue(StorageBlock.FACING,facing)
                    .getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            var center=Vec3.atCenterOf(BlockPos.ZERO);
            var ray=outline.clip(center.relative(facing,2),center,BlockPos.ZERO);
            if (ray==null || ray.getDirection()!=facing
                    || ray.getLocation().distanceToSqr(center.relative(facing,6.5/16))>1e-10)
                throw new AssertionError("Could not target the equator from "+facing);
            var top=outline.clip(center.add(0,1.5,0),center,BlockPos.ZERO);
            if (top==null || top.getDirection()!=Direction.UP || Math.abs(top.getLocation().y-15.25/16)>1e-7)
                throw new AssertionError("Ray missed the crown");
            Vec3 corner=new Vec3(.02,1.5,.02);
            if (outline.clip(corner,corner.add(0,-2,0),BlockPos.ZERO)!=null)
                throw new AssertionError("Empty space beside globe should not consume clicks");
        }
        System.out.println("PASS public globe equator, crown and outside rays in all four blockstates");
    }
}
