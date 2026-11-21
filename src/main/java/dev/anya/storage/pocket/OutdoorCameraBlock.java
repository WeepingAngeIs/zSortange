package dev.anya.storage.pocket;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** FACING is the original attachment normal. Old blocks default to a straight head. */
final class OutdoorCameraBlock extends CameraDevices.DeviceBlock {
    static final IntegerProperty PAN = IntegerProperty.create("pan", 0, 2);
    private static final Map<Direction,VoxelShape[]> SHAPES = new EnumMap<>(Direction.class);
    static {
        for (Direction mount : Direction.values()) {
            VoxelShape plate = boxFacing(mount, 5,2,14, 11,8,16);
            VoxelShape arm = boxFacing(mount, 7,3,7, 9,5,14);
            VoxelShape pivot = boxFacing(mount, 6.5,5,6.5, 9.5,5.75,9.5);
            VoxelShape[] poses = new VoxelShape[3];
            for (int pan=0; pan<poses.length; pan++) {
                Direction face = lensDirection(mount,pan);
                poses[pan] = Shapes.or(plate, arm, pivot,
                        boxFacing(face,4.5,5.75,2.5, 11.5,10.25,11),
                        boxFacing(face,4,10.25,.5, 12,11,11),
                        boxFacing(face,5.5,6,1, 10.5,10,2.5),
                        boxFacing(face,6,6.5,0, 10,9.5,1)).optimize();
            }
            SHAPES.put(mount,poses);
        }
    }

    OutdoorCameraBlock() {
        registerDefaultState(defaultBlockState().setValue(PAN,0));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PAN);
    }
    static Direction lensDirection(BlockState state) {
        return lensDirection(state.getValue(BlockStateProperties.FACING),state.getValue(PAN));
    }
    private static Direction lensDirection(Direction mount,int pan) {
        if (!mount.getAxis().isHorizontal()) return mount;
        return switch (pan) {
            case 1 -> mount.getClockWise();
            case 2 -> mount.getCounterClockWise();
            default -> mount;
        };
    }
    private static Vec3 orient(Direction face,double x,double y,double z) {
        return switch (face) {
            case NORTH -> new Vec3(x,y,z);
            case EAST  -> new Vec3(16-z,y,x);
            case SOUTH -> new Vec3(16-x,y,16-z);
            case WEST  -> new Vec3(z,y,16-x);
            case UP    -> new Vec3(x,16-z,y);
            case DOWN  -> new Vec3(x,z,16-y);
        };
    }
    private static VoxelShape boxFacing(Direction face,double x0,double y0,double z0,double x1,double y1,double z1) {
        Vec3 a=orient(face,x0,y0,z0), b=orient(face,x1,y1,z1);
        return Block.box(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z),
                Math.max(a.x,b.x),Math.max(a.y,b.y),Math.max(a.z,b.z));
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return SHAPES.get(state.getValue(BlockStateProperties.FACING))[state.getValue(PAN)];
    }
    // The selection and collision volumes follow the model. Its empty corners transmit skylight.
    @Override protected boolean useShapeForLightOcclusion(BlockState state) { return true; }
}
