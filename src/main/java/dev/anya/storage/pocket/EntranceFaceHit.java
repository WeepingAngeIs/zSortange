package dev.anya.storage.pocket;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class EntranceFaceHit {
    static boolean accepts(BlockHitResult hit, boolean singleMarker) {
        // The separate sampled-marker experiment was scoped to horizontal viewing.
        return hit.getType() == HitResult.Type.BLOCK && !hit.isInside()
                && (!singleMarker || hit.getDirection().getAxis().isHorizontal());
    }

    private EntranceFaceHit() {}
}
