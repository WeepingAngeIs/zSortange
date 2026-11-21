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
            VoxelShape bracket = Shapes.or(
                    boxFacing(mount,6.125,5,15.5, 9.875,6.875,16),
                    boxFacing(mount,6.75,5.5,7.5, 7.375,6.125,15.5),
                    boxFacing(mount,8.625,5.5,7.5, 9.25,6.125,15.5),
                    boxFacing(mount,6.75,6.125,7.5, 9.25,7,8.5));
            VoxelShape[] poses = new VoxelShape[3];
            for (int pan=0; pan<poses.length; pan++) {
                Direction face = lensDirection(mount,pan);
                poses[pan] = Shapes.or(bracket,
                        boxFacing(face,6,7,5.875, 10,9.125,10.5),
                        boxFacing(face,5.875,9.125,5.125, 10.125,9.5,10.625),
                        boxFacing(face,6.625,7.125,5, 9.375,8.875,5.875),
                        boxFacing(face,6.875,7.25,4.5, 9.125,8.75,5)).optimize();
            }
            SHAPES.put(mount,poses);
        }
    }

    OutdoorCameraBlock() {
        registerDefaultState(defaultBlockState().setValue(PAN,0));
    }
    @Override public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T>
    getTicker(net.minecraft.world.level.Level level,BlockState state,net.minecraft.world.level.block.entity.BlockEntityType<T> type){
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)||type!=CameraDevices.TYPE.get())return null;
        return (world,pos,block,entity)->CameraTracking.tick(server,(CameraDevices.Device)entity);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PAN);
    }
    static Direction lensDirection(BlockState state) {
        return lensDirection(state.getValue(BlockStateProperties.FACING),state.getValue(PAN));
    }
    static Vec3 lensEye(BlockPos pos, BlockState state) {
        // The front glass is 3.5 model units ahead of the block center.
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(lensDirection(state).getNormal()).scale(3.5 / 16 + .015625));
    }
    static Direction lensDirection(Direction mount,int pan) {
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
