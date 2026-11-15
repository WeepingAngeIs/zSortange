package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import tschipp.carryon.common.carry.*;
import xaero.pac.common.server.api.OpenPACServerAPI;
import java.util.*;

/** The exact real dependencies are present; these are server-side API/event tests, not a full pack. */
public final class Integrations {
    public static void verify(MinecraftServer server,ServerPlayer owner,StorageEntity hub,PocketContent.EntranceEntity entrance,PocketLedger.Pocket pocket)throws Exception{
        Acceptance.require(ModList.get().isLoaded("openpartiesandclaims")&&ModList.get().isLoaded("carryon"),"Missing acceptance dependencies");
        for(String id:List.of("openpartiesandclaims","carryon"))AnyaStorage.LOG.info("ANYA_DEPENDENCY {} {}",id,ModList.get().getModContainerById(id).orElseThrow().getModInfo().getVersion());
        var api=OpenPACServerAPI.get(server);var claims=api.getServerClaimsManager();var target=PocketService.dimension(server);BlockPos cp=PocketService.chestPositions(pocket).getFirst();
        target.getChunk(cp.getX()>>4,cp.getZ()>>4); // Fixture preparation only; the production check remains loaded-only.
        var dim=target.dimension().location();UUID stranger=UUID.fromString("6558b4b4-f88a-45d5-85b4-200000000001");
        Acceptance.require(Safety.locationReason(owner,target,cp).isEmpty(),"Unclaimed cross-dimensional control failed: "+Safety.status());
        claims.claim(dim,stranger,0,cp.getX()>>4,cp.getZ()>>4,false);
        Acceptance.require(!Safety.locationReason(owner,target,cp).isEmpty(),"OPAC cross-dimensional foreign claim did not deny");
        Acceptance.require(Safety.locationReason(owner,server.overworld(),hub.getBlockPos()).isEmpty(),"Target claim leaked into source dimension");
        claims.unclaim(dim,cp.getX()>>4,cp.getZ()>>4);
        claims.claim(dim,owner.getUUID(),0,cp.getX()>>4,cp.getZ()>>4,false);
        Acceptance.require(Safety.locationReason(owner,target,cp).isEmpty(),"Own destination claim denied");claims.unclaim(dim,cp.getX()>>4,cp.getZ()>>4);
        AnyaStorage.LOG.info("ANYA_OPAC_CROSS_DIMENSION_OK: real 0.33.0 API, foreign destination denial, same-coordinate home allowance, owner destination allowance; no force-loading claims");
        var carry=CarryOnDataManager.getCarryData(owner);carry.setBlock(Blocks.STONE.defaultBlockState(),null);CarryOnDataManager.setCarryData(owner,carry);
        Acceptance.require(!PocketTravelGuard.reason(owner).isEmpty(),"Carried object did not block entry");
        PocketService.enter(owner,entrance);Acceptance.require(PocketService.lease(owner.getUUID())==null,"Carried object acquired entry lease");
        Acceptance.require(CarryOnDataManager.getCarryData(owner).isCarrying(),"Denied travel erased carried content");carry.clear();CarryOnDataManager.setCarryData(owner,carry);
        Acceptance.require(PocketTravelGuard.reason(owner).isEmpty(),"Empty Carry On state blocks entry");
        AnyaStorage.LOG.info("ANYA_CARRY_TRAVEL_OK: real dependency carrying state denies entry, preserves held state, empty state allowed");
    }
    public static void inside(ServerPlayer p,PocketLedger.Pocket record)throws Exception{
        var cp=PocketService.chestPositions(record).getFirst();p.connection.teleport(cp.getX()+.5,65,cp.getZ()+2,180,20);
        ItemStack held=p.getMainHandItem().copy();p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);p.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
        var data=CarryOnDataManager.getCarryData(p);data.setKeyPressed(true);CarryOnDataManager.setCarryData(p,data);
        var chest=p.level().getBlockEntity(cp);var event=new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,cp,new BlockHitResult(Vec3.atCenterOf(cp),Direction.UP,cp,false));
        NeoForge.EVENT_BUS.post(event);
        Acceptance.require(event.isCanceled()&&p.level().getBlockEntity(cp)==chest&&!CarryOnDataManager.getCarryData(p).isCarrying(),"Carry On moved protected pocket chest");
        data.setKeyPressed(false);CarryOnDataManager.setCarryData(p,data);p.setItemInHand(InteractionHand.MAIN_HAND,held);p.closeContainer();
        AnyaStorage.LOG.info("ANYA_CARRY_PHYSICAL_OK: actual NeoForge right-click bus including Carry On; original block entity retained, no carried copy");
    }
}
