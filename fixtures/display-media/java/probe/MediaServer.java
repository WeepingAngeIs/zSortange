package probe;

import dev.anya.storage.Content;
import dev.anya.storage.pocket.CameraDevices;
import java.nio.file.Files;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid="anyadisplayprobe")
public final class MediaServer {
    private static int stage;
    private static void place(ServerPlayer p, BlockPos at, Block block, Direction face) {
        var level = p.serverLevel();
        var state = block.defaultBlockState().setValue(BlockStateProperties.FACING, face);
        if (!level.setBlockAndUpdate(at,state)) throw new IllegalStateException("Failed to place display at " + at);
        block.setPlacedBy(level,at,state,p,new ItemStack(block));
    }
    private static void position(ServerPlayer p, BlockPos at) {
        var level = p.serverLevel();
        var feet = at.offset(0,-1,3);
        level.setBlockAndUpdate(feet.below(), Blocks.SMOOTH_STONE.defaultBlockState());
        p.teleportTo(level,feet.getX()+.5,feet.getY(),feet.getZ()+.55,180f,1f);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e) throws Exception {
        var s = e.getServer();
        var p = s.getPlayerList().getPlayerByName("DisplayOwner");
        if (p==null) return;
        var l = p.serverLevel();
        if (stage==0) {
            MediaTrial.hosted();
            s.overworld().setDayTime(6000);
            p.setGameMode(GameType.SURVIVAL);
            p.getInventory().clearContent();
            var cam = MediaTrial.CAMERA;
            for (int x=-4;x<=4;x++) for(int y=-4;y<=4;y++)
                l.setBlockAndUpdate(cam.north(3).offset(x,y,0),Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState());
            l.setBlockAndUpdate(cam.south(),Blocks.STONE.defaultBlockState());
            place(p,cam,CameraDevices.CAMERA.get(),Direction.NORTH);
            l.setBlockAndUpdate(MediaTrial.TV.below(),Blocks.QUARTZ_BLOCK.defaultBlockState());
            place(p,MediaTrial.TV,CameraDevices.TV.get(),Direction.SOUTH);
            for (int x=-1;x<=3;x++) for (int y=-1;y<=2;y++)
                l.setBlockAndUpdate(MediaTrial.WALL.north().offset(x,y,0),Blocks.SMOOTH_STONE.defaultBlockState());
            place(p,MediaTrial.WALL,CameraDevices.WIDE.get(),Direction.SOUTH);
            p.teleportTo(l,cam.getX()+1.5,cam.getY()-1,cam.getZ()+.5,90,0);
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.CUPID_TABLET.get()));
            p.setShiftKeyDown(true);
            p.gameMode.useItemOn(p,l,p.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(cam),Direction.EAST,cam,false));
            p.setShiftKeyDown(false);
            p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            position(p,MediaTrial.TV);
            stage++; Files.writeString(MediaTrial.proof.resolve("stage-tv"),"tabletop");
        } else if(stage==1 && Files.exists(MediaTrial.proof.resolve("next-wall"))) {
            position(p,MediaTrial.WALL);
            stage++; Files.writeString(MediaTrial.proof.resolve("stage-wall"),"wide");
        } else if(stage==2 && Files.exists(MediaTrial.proof.resolve("fit-during-movie"))){
            var block=l.getBlockEntity(MediaTrial.WALL);
            var resize=Class.forName("dev.anya.storage.pocket.WallMonitorBlock").getDeclaredMethod("resize",ServerPlayer.class,
                    CameraDevices.Device.class,int.class,int.class,boolean.class);
            resize.setAccessible(true);
            String message=(String)resize.invoke(null,p,block,3,2,true);
            if(!message.isEmpty())throw new IllegalStateException(message);
            stage++;
        }
    }
}
