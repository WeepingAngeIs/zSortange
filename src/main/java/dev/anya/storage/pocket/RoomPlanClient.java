package dev.anya.storage.pocket;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.StorageBlock;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid=AnyaStorage.ID, value=Dist.CLIENT)
public final class RoomPlanClient {
    private static List<RoomPlanPacket.Outline> outlines = List.of();
    private static ClientLevel level;
    static {
        RoomPlanPacket.receiver = (connection,packet) -> {
            var mc = Minecraft.getInstance();
            if (mc.getConnection() == null || connection != mc.getConnection().getConnection()) return;
            level = mc.level;
            outlines = level != null && PocketService.inPocket(level) ? packet.marks() : List.of();
        };
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == level) { outlines = List.of(); level = null; }
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (outlines.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (level == null || mc.level != level || mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()
                || event.getLevelRenderer() != mc.levelRenderer || event.getCamera() != mc.gameRenderer.getMainCamera()
                || event.getCamera().getEntity() != mc.player) return;
        var buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(-camera.x,-camera.y,-camera.z);
            for (var mark : outlines) {
                if (!level.hasChunkAt(mark.pos())) continue;
                var state = Block.stateById(mark.state());
                if (!RoomEditor.supported(state.getBlock())) continue;
                var shape = state.getShape(level,mark.pos());
                for (var box : shape.toAabbs())
                    LevelRenderer.renderLineBox(pose, lines, box.move(mark.pos()).inflate(.002), .37f, .96f, .92f, .85f);
                if (state.hasProperty(StorageBlock.FACING)) {
                    var facing = state.getValue(StorageBlock.FACING);
                    var front = net.minecraft.world.phys.Vec3.atCenterOf(mark.pos()).relative(facing,.49);
                    var handle = net.minecraft.world.phys.AABB.ofSize(front, facing.getStepX()==0 ? .34 : .035,
                            .085, facing.getStepZ()==0 ? .34 : .035);
                    LevelRenderer.renderLineBox(pose, lines, handle, 1f,.69f,.82f,.95f);
                }
            }
        } finally { pose.popPose(); }
        buffers.endBatch(RenderType.lines());
    }
    private RoomPlanClient() {}
}
