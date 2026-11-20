package dev.anya.storage.furniture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anya.storage.Content;
import dev.anya.storage.StorageBlock;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Compare actual generated exterior faces and player interaction rays upright in every blockstate. */
final class ShelfGeometryCheck {
    private static JsonObject resource(String name) throws IOException {
        var resource = Objects.requireNonNull(ShelfGeometryCheck.class.getResourceAsStream("/assets/anyastorage/" + name));
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static double axis(JsonArray xyz, int i) { return xyz.get(i).getAsDouble() / 16; }

    private static AABB rotated(AABB box, int degrees) {
        return switch (degrees) {
            case 0 -> box;
            case 90 -> new AABB(1-box.maxZ,box.minY,box.minX,1-box.minZ,box.maxY,box.maxX);
            case 180 -> new AABB(1-box.maxX,box.minY,1-box.maxZ,1-box.minX,box.maxY,1-box.minZ);
            case 270 -> new AABB(box.minZ,box.minY,1-box.maxX,box.maxZ,box.maxY,1-box.minX);
            default -> throw new AssertionError("Unexpected shelf facing angle " + degrees);
        };
    }

    private static boolean sameFaceOverlap(AABB a, AABB b, Direction side) {
        var normal = side.getAxis();
        boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        if ((positive ? a.max(normal)-b.max(normal) : a.min(normal)-b.min(normal)) != 0) return false;
        for (var axis : Direction.Axis.values()) {
            if (axis != normal && Math.min(a.max(axis),b.max(axis)) <= Math.max(a.min(axis),b.min(axis))) return false;
        }
        return true;
    }

    static void run() throws IOException {
        var elements = resource("models/block/keepsake_shelf.json").getAsJsonArray("elements");
        var variants = resource("blockstates/keepsake_shelf.json").getAsJsonObject("variants");
        var shelf = Content.SHELF.get();
        for (var facing : Direction.Plane.HORIZONTAL) {
            int rotation = variants.getAsJsonObject("facing=" + facing.getSerializedName()).get("y").getAsInt();
            List<AABB> prior = new ArrayList<>();
            var exterior = Shapes.empty();
            for (var element : elements) {
                var block = element.getAsJsonObject();
                if (block.getAsJsonObject("faces").size() != 6) throw new AssertionError("Expected a closed shelf cuboid");
                var lo = block.getAsJsonArray("from");
                var hi = block.getAsJsonArray("to");
                var box = rotated(new AABB(axis(lo,0),axis(lo,1),axis(lo,2),axis(hi,0),axis(hi,1),axis(hi,2)),rotation);
                for (var other : prior) for (var side : Direction.values()) {
                    if (sameFaceOverlap(box,other,side))
                        throw new AssertionError("Coplanar shelf faces at " + facing + " on " + side + ": " + box + " / " + other);
                }
                prior.add(box);
                exterior = Shapes.or(exterior,Shapes.create(box));
            }
            var outline = shelf.defaultBlockState().setValue(StorageBlock.FACING,facing)
                    .getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            if (Shapes.joinIsNotEmpty(exterior,outline,BooleanOp.NOT_SAME)) throw new AssertionError("Shelf outline differs at " + facing);
            var center = Vec3.atCenterOf(BlockPos.ZERO);
            var ray = outline.clip(center.relative(facing,2),center.relative(facing.getOpposite(),.5),BlockPos.ZERO);
            if (ray == null || ray.getDirection()!=facing || ray.getLocation().distanceToSqr(center.relative(facing.getOpposite(),.25)) > 1e-10)
                throw new AssertionError("Front ray should pass above the seat and reach the backboard for " + facing);
            var top = outline.clip(center.add(0,1.5,0),center.add(0,-1,0),BlockPos.ZERO);
            if (top == null || top.getDirection()!=Direction.UP || Math.abs(top.getLocation().y-4.35/16)>1e-7)
                throw new AssertionError("Top ray should target the actual display mat for " + facing);
        }
        System.out.println("PASS four actual shelf facings: no same-facing coplanar overlaps, exact outline geometry, front and top shelf rays");
    }
}
