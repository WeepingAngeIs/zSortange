package dev.anya.storage.pocket;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.anya.storage.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.*;
import java.util.*;

/** Conservative protection envelope for the gated room prototype, not a generic claims replacement. */
@EventBusSubscriber(modid=AnyaStorage.ID)
public final class PocketEvents {
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){PocketService.tick(e.getServer());}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){PocketService.stop(e.getServer());}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(e.getEntity() instanceof ServerPlayer p)PocketService.login(p);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p)PocketService.logout(p);}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent e){if(e.getEntity() instanceof ServerPlayer p)PocketService.login(p);}
    @SubscribeEvent public static void death(LivingDeathEvent e){if(e.getEntity() instanceof ServerPlayer p&&PocketService.inPocket(p.level()))PocketService.died(p);}
    @SubscribeEvent public static void drops(LivingDropsEvent e){
        if(e.getEntity() instanceof ServerPlayer p&&PocketService.inPocket(p.level()))for(ItemEntity item:e.getDrops()){item.setTarget(p.getUUID());item.getPersistentData().putUUID("AnyaPocketDropOwner",p.getUUID());}
    }
    @SubscribeEvent public static void toss(ItemTossEvent e){if(PocketService.inPocket(e.getPlayer().level())){e.getEntity().setTarget(e.getPlayer().getUUID());e.getEntity().getPersistentData().putUUID("AnyaPocketDropOwner",e.getPlayer().getUUID());}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pickup(ItemEntityPickupEvent.Pre e){
        if(!(e.getPlayer() instanceof ServerPlayer p)||!PocketService.inPocket(p.level()))return;
        var data=e.getItemEntity().getPersistentData();
        boolean allowed=data.hasUUID("AnyaPocketDropOwner")&&data.getUUID("AnyaPocketDropOwner").equals(p.getUUID());
        if(!data.hasUUID("AnyaPocketDropOwner"))try{
            var l=PocketService.lease(p.getUUID());var r=l==null?null:PocketService.ledger(p.getServer()).get(l.pocket());
            allowed=r!=null&&l.kind()==PocketLeases.Kind.VISIT&&PocketService.authorize(p,r,l.kind(),false,BasePermissions.WITHDRAW).isEmpty();
        }catch(java.io.IOException ignored){}
        if(!allowed)e.setCanPickup(TriState.FALSE); // Never force TRUE past vanilla or another mod's denial.
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void damage(LivingIncomingDamageEvent e){
        if(e.getEntity() instanceof ServerPlayer p&&PocketService.inPocket(p.level()))e.setCanceled(true); // Safe prototype room; /kill/death lifecycle still has independent cleanup.
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void breakBlock(BlockEvent.BreakEvent e){
        if(e.getLevel() instanceof Level level&&PocketService.inPocket(level)){e.setCanceled(true);return;}
        if(e.getLevel().getBlockEntity(e.getPos()) instanceof StorageEntity chest&&chest.pocketId!=null){e.setCanceled(true);return;}
        if(e.getLevel().getBlockEntity(e.getPos()) instanceof PocketContent.EntranceEntity entrance&&e.getPlayer() instanceof ServerPlayer p){
            if(!Objects.equals(entrance.owner,p.getUUID())||!Safety.locationReason(p,p.serverLevel(),e.getPos()).isEmpty())e.setCanceled(true);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void place(BlockEvent.EntityPlaceEvent e){if(e.getLevel() instanceof Level level&&PocketService.inPocket(level))e.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void piston(PistonEvent.Pre e){if(e.getLevel() instanceof Level level&&PocketService.inPocket(level))e.setCanceled(true);}
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate e){if(PocketService.inPocket(e.getLevel())){e.getAffectedBlocks().clear();e.getAffectedEntities().clear();}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void join(EntityJoinLevelEvent e){
        if(PocketService.inPocket(e.getLevel())&&!(e.getEntity() instanceof net.minecraft.world.entity.player.Player)&&!(e.getEntity() instanceof ItemEntity))e.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void travel(EntityTravelToDimensionEvent e){
        if(!PocketService.ownTeleport()&&(PocketService.inPocket(e.getEntity().level())||e.getDimension().equals(PocketService.DIMENSION)))e.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void useBlock(PlayerInteractEvent.RightClickBlock e){
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        var be=p.level().getBlockEntity(e.getPos());
        if(PocketService.inPocket(p.level())){
            e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);
            var l=PocketService.lease(p.getUUID());
            try{
                var r=l==null?null:PocketService.ledger(p.getServer()).get(l.pocket());
                if(r!=null&&e.getPos().equals(PocketService.exitPosition(r))){PocketService.recover(p);return;}
            }catch(java.io.IOException ignored){}
            if(be instanceof StorageEntity chest&&chest.pocketId!=null)PocketService.openChest(p,chest);
        }else if(be instanceof PocketContent.EntranceEntity entrance){
            e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);
            if(p.isShiftKeyDown()&&e.getItemStack().is(Content.REMOTE.get()))PocketService.link(p,entrance,e.getItemStack());else PocketService.enter(p,entrance);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void useItem(PlayerInteractEvent.RightClickItem e){
        if(PocketService.inPocket(e.getLevel())&&!e.getItemStack().is(Content.BACKPACK.get())&&!e.getItemStack().is(Content.GUIDE.get())){e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void entityUse(PlayerInteractEvent.EntityInteract e){if(PocketService.inPocket(e.getLevel()))e.setCanceled(true);}
    private static PocketContent.EntranceEntity lookingAt(ServerPlayer p){
        if(p.pick(6,0,false) instanceof BlockHitResult hit&&p.serverLevel().hasChunkAt(hit.getBlockPos())&&p.serverLevel().getBlockEntity(hit.getBlockPos()) instanceof PocketContent.EntranceEntity e)return e;
        throw new IllegalArgumentException("Look at your nearby home void cube");
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){
        e.getDispatcher().register(Commands.literal("anya").then(Commands.literal("pocket")
            .executes(c->{var p=c.getSource().getPlayerOrException();AnyaStorage.tell(p,PocketService.status());try{for(var r:PocketService.ledger(p.getServer()).pockets())if(r.owner().equals(p.getUUID()))AnyaStorage.tell(p,r.id()+" | "+r.stage()+" | "+r.theme());}catch(java.io.IOException ex){AnyaStorage.tell(p,ex.getMessage());}return 1;})
            .then(Commands.literal("exit").executes(c->{var p=c.getSource().getPlayerOrException();try{if(PocketService.inPocket(p.level())||PocketService.ledger(p.getServer()).returning(p.getUUID())!=null)PocketService.recover(p);else AnyaStorage.tell(p,"No unfinished pocket visit to recover.");}catch(java.io.IOException ex){if(PocketService.inPocket(p.level()))PocketService.recover(p);else AnyaStorage.tell(p,ex.getMessage());}return 1;}))
            .then(Commands.literal("reanchor").then(Commands.argument("identity",StringArgumentType.word()).executes(c->{var p=c.getSource().getPlayerOrException();try{PocketService.reanchor(p,UUID.fromString(StringArgumentType.getString(c,"identity")),lookingAt(p),p.getMainHandItem());}catch(IllegalArgumentException ex){AnyaStorage.tell(p,ex.getMessage());}return 1;})))
            .then(Commands.literal("theme").then(Commands.argument("name",StringArgumentType.word()).executes(c->{var p=c.getSource().getPlayerOrException();try{PocketService.theme(p,lookingAt(p).pocket,StringArgumentType.getString(c,"name"));}catch(IllegalArgumentException ex){AnyaStorage.tell(p,ex.getMessage());}return 1;})))
        ));
    }
    private PocketEvents(){}
}
