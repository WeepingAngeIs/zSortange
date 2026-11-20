package dev.anya.storage.pocket;

/** Client-only local decoration. No destination, image download or chunk-loading path exists here. */
@net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
public final class PocketPreviewParticles {
    private static long frameTick=Long.MIN_VALUE;
    private static int emitted;
    public static void emit(net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos,net.minecraft.util.RandomSource random){
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.player==null||!dev.anya.storage.appearance.ClientLooks.options.particles||mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>256||random.nextInt(8)!=0)return;
        long now=level.getGameTime();if(frameTick!=now){frameTick=now;emitted=0;}
        if(emitted>=4)return;emitted++;
        level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,pos.getX()+random.nextDouble(),pos.getY()+1.05,pos.getZ()+random.nextDouble(),0,.006,0);
    }
    private PocketPreviewParticles(){}
}
