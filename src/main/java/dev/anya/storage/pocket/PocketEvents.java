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
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){PocketService.tick(e.getServer());RoomPlans.tick(e.getServer());RoomEditor.tick(e.getServer());}
    @SubscribeEvent public static void checkNativeMenus(ServerTickEvent.Pre e){for(var p:e.getServer().getPlayerList().getPlayers())NativeRoomBuild.checkMenu(p);}
    @SubscribeEvent public static void stop(ServerStoppingEvent e){PocketService.stop(e.getServer());}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(e.getEntity() instanceof ServerPlayer p)PocketService.login(p);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p)PocketService.logout(p);}
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent e){if(e.getEntity() instanceof ServerPlayer p)PocketService.login(p);}
    @SubscribeEvent public static void death(LivingDeathEvent e){if(e.getEntity() instanceof ServerPlayer p&&PocketService.inPocket(p.level()))PocketService.died(p);}
    @SubscribeEvent public static void drops(LivingDropsEvent e){
        if(e.getEntity() instanceof ServerPlayer p&&PocketService.inPocket(p.level()))for(ItemEntity item:e.getDrops()){item.setTarget(p.getUUID());item.getPersistentData().putUUID("AnyaPocketDropOwner",p.getUUID());}
    }
    @SubscribeEvent public static void toss(ItemTossEvent e){if(PocketService.inPocket(e.getPlayer().level())){e.getEntity().setTarget(e.getPlayer().getUUID());e.getEntity().getPersistentData().putUUID("AnyaPocketDropOwner",e.getPlayer().getUUID());}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void buildingDrops(BlockDropsEvent e){
        if(PocketService.inPocket(e.getLevel()) && e.getBreaker() instanceof ServerPlayer p && RoomEditor.active(p)){
            for(ItemEntity item:e.getDrops()){
                item.setTarget(p.getUUID());
                item.getPersistentData().putUUID("AnyaPocketDropOwner",p.getUUID());
            }
        }
    }
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
        if(RoomEditor.permissionEvent(e))return;
        if(e.getLevel() instanceof Level level&&PocketService.inPocket(level)){
            if(!(e.getPlayer() instanceof ServerPlayer p) || NativeRoomBuild.special(e.getState()) || !NativeRoomBuild.breakable(p,e.getPos()))e.setCanceled(true);
            return;
        }
        if(e.getLevel().getBlockEntity(e.getPos()) instanceof StorageEntity chest&&chest.pocketId!=null){e.setCanceled(true);return;}
        if(e.getLevel().getBlockEntity(e.getPos()) instanceof PocketContent.EntranceEntity entrance&&e.getPlayer() instanceof ServerPlayer p){
            if(!Objects.equals(entrance.owner,p.getUUID())||!Safety.locationReason(p,p.serverLevel(),e.getPos()).isEmpty())e.setCanceled(true);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void place(BlockEvent.EntityPlaceEvent e){if(!RoomEditor.permissionEvent(e)&&e.getLevel() instanceof Level level&&PocketService.inPocket(level)
                && (!(e.getEntity() instanceof ServerPlayer p)||!NativeRoomBuild.place(p,e)))e.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void piston(PistonEvent.Pre e){if(e.getLevel() instanceof Level level&&PocketService.inPocket(level))e.setCanceled(true);}
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate e){if(PocketService.inPocket(e.getLevel())){e.getAffectedBlocks().clear();e.getAffectedEntities().clear();}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void join(EntityJoinLevelEvent e){
        if(PocketService.inPocket(e.getLevel())&&!(e.getEntity() instanceof net.minecraft.world.entity.player.Player)&&!(e.getEntity() instanceof ItemEntity))e.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void travel(EntityTravelToDimensionEvent e){
        if(!PocketService.ownTeleport()&&(PocketService.inPocket(e.getEntity().level())||e.getDimension().equals(PocketService.DIMENSION)))e.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void clickToRemove(PlayerInteractEvent.LeftClickBlock e){
        if(!PocketService.inPocket(e.getLevel()))return;
        boolean denied=e.getUseBlock().isFalse() || e.getUseItem().isFalse();
        boolean special=NativeRoomBuild.special(e.getLevel().getBlockState(e.getPos()));
        if(!denied && !special
                && (e.getLevel().isClientSide || e.getEntity() instanceof ServerPlayer p && NativeRoomBuild.breakable(p,e.getPos())))return;
        // NeoForge still sends START, but suppresses client mining, block callbacks, and predicted Creative breaks.
        e.setCanceled(true);
        if(!denied && special && e.getEntity() instanceof ServerPlayer p && e.getAction()==PlayerInteractEvent.LeftClickBlock.Action.START)
            RoomEditor.click(p,e.getPos(),e.getFace(),true);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void useBlock(PlayerInteractEvent.RightClickBlock e){
        boolean denied=e.getUseBlock().isFalse() || e.getUseItem().isFalse();
        if(!denied && !(e.getItemStack().getItem() instanceof net.minecraft.world.item.BlockItem)){
            var entity=e.getLevel().getBlockEntity(e.getPos());
            var device=entity instanceof CameraDevices.Device d?d:null;
            if(device==null&&(e.getItemStack().isEmpty()||StorageItem.isTablet(e.getItemStack())))
                device=WallPanel.atHit(e.getLevel(),e.getHitVec(),e.getEntity().getEyePosition(),e.getEntity().getLookAngle());
            if(device!=null){
                if(e.getEntity() instanceof ServerPlayer player)CameraDevices.use(player,device,e.getHand());
                e.setCanceled(true);e.setCancellationResult(InteractionResult.sidedSuccess(e.getLevel().isClientSide));return;
            }
        }
        if(PocketService.inPocket(e.getLevel()) && !denied){
            var target=e.getLevel().getBlockState(e.getPos());
            var held=e.getItemStack();
            boolean nativeItem=NativeRoomBuild.useNative(held);
            if(target.is(net.minecraft.world.level.block.Blocks.CHEST)
                    && (!e.getEntity().isSecondaryUseActive() || e.getEntity().getMainHandItem().isEmpty() && e.getEntity().getOffhandItem().isEmpty())){
                boolean opened=e.getLevel().isClientSide || e.getEntity() instanceof ServerPlayer p && NativeRoomBuild.openChest(p,e.getPos());
                e.setCanceled(true);e.setCancellationResult(opened?InteractionResult.sidedSuccess(e.getLevel().isClientSide):InteractionResult.FAIL);return;
            }
            boolean ordinaryInteraction=!NativeRoomBuild.special(target) && !target.is(net.minecraft.world.level.block.Blocks.LODESTONE)
                    && !RoomEditor.supported(target.getBlock()) && !nativeItem && !(held.getItem() instanceof net.minecraft.world.item.BlockItem);
            if(nativeItem || ordinaryInteraction){
                if(e.getLevel().isClientSide)return;
                if(e.getEntity() instanceof ServerPlayer p){
                    if(nativeItem && !NativeRoomBuild.placeableStack(held)){
                        p.displayClientMessage(Component.literal("This block item has custom data. It stays in your hand."),true);
                    }else if(nativeItem && ((!NativeRoomBuild.special(target) && !target.is(net.minecraft.world.level.block.Blocks.LODESTONE)) || p.isSecondaryUseActive())){
                        var ctx=new net.minecraft.world.item.context.BlockPlaceContext(p,e.getHand(),held,e.getHitVec());
                        var state=((net.minecraft.world.item.BlockItem)held.getItem()).getBlock().defaultBlockState();
                        if(state.getBlock() instanceof WallMonitorBlock monitor)state=monitor.getStateForPlacement(ctx);
                        if(state!=null && state.is(net.minecraft.world.level.block.Blocks.CHEST) && !NativeRoomBuild.canAddChest(p)){
                            p.displayClientMessage(Component.literal("No free chest capacity in this room."),true);
                            e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);return;
                        }
                        if(state!=null&&NativeRoomBuild.cell(p,ctx.getClickedPos(),state,false,state.hasBlockEntity()&&!state.is(net.minecraft.world.level.block.Blocks.CHEST))){
                            // Only the held block item may run here; container use is a separate authorized click.
                            e.setUseBlock(TriState.FALSE);return;
                        }
                    }else if(ordinaryInteraction && NativeRoomBuild.cell(p,e.getPos(),target,false,target.hasBlockEntity())){
                        NativeRoomBuild.watch(p,e.getPos());e.setUseItem(TriState.FALSE);return;
                    }
                }
                if((!NativeRoomBuild.special(target) && !target.is(net.minecraft.world.level.block.Blocks.LODESTONE)) || e.getEntity().isSecondaryUseActive()){
                    e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);return;
                }
            }
        }
        if(e.getLevel().isClientSide && PocketService.inPocket(e.getLevel())){
            // The use-on packet still goes to the server. Plain surfaces also allow the usual air-use
            // fallback for the tablet, backpack, guide, or an empty main hand with an item in the offhand.
            var held=e.getItemStack();
            var state=e.getLevel().getBlockState(e.getPos());
            boolean interactive=state.is(Content.CRATE.get()) || state.is(net.minecraft.world.level.block.Blocks.LODESTONE)
                    || dev.anya.storage.furniture.Furniture.isFurniture(state.getBlock());
            boolean useInAir=held.isEmpty() || StorageItem.isTerminal(held) || held.is(Content.BACKPACK.get()) || held.is(Content.GUIDE.get());
            e.setCanceled(true);
            e.setCancellationResult(denied ? InteractionResult.FAIL : !interactive && useInAir ? InteractionResult.PASS : InteractionResult.SUCCESS);
            return;
        }
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        if(PocketService.inPocket(p.level())&&!p.serverLevel().hasChunkAt(e.getPos())){
            e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);return;
        }
        var be=p.level().getBlockEntity(e.getPos());
        if(denied && (PocketService.inPocket(p.level()) || be instanceof PocketContent.EntranceEntity)){
            e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);return;
        }
        if(PocketService.inPocket(p.level())){
            e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);
            var l=PocketService.lease(p.getUUID());
            try{
                var r=l==null?null:PocketService.ledger(p.getServer()).get(l.pocket());
                if(r!=null&&e.getPos().equals(PocketService.exitPosition(r))){PocketService.recover(p);return;}
            }catch(java.io.IOException ignored){}
            if(be instanceof StorageEntity chest&&chest.pocketId!=null)PocketService.openChest(p,chest);
            else if(be instanceof dev.anya.storage.furniture.FurnitureEntity furniture && e.getHand()==net.minecraft.world.InteractionHand.MAIN_HAND)
                dev.anya.storage.furniture.Furniture.use(p,furniture);
            else if(e.getHand()==net.minecraft.world.InteractionHand.MAIN_HAND && e.getItemStack().getItem() instanceof net.minecraft.world.item.BlockItem
                    && RoomEditor.click(p,e.getPos(),e.getFace(),false))return;
            else if(e.getHand()==net.minecraft.world.InteractionHand.MAIN_HAND&&e.getItemStack().is(Content.CRATE.get().asItem()))ChestPadGuide.show(p);
        }else if(be instanceof PocketContent.EntranceEntity entrance){
            e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);
            if(p.isShiftKeyDown()&&StorageItem.isTerminal(e.getItemStack())){
                var tablet=StorageItem.tabletInHand(p,e.getHand());
                if(StorageItem.permittedTablet(p,tablet)) PocketService.link(p,entrance,tablet);
                else p.displayClientMessage(net.minecraft.network.chat.Component.literal("You cannot use this tablet right now."),true);
            }else PocketService.enter(p,entrance);
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void useItem(PlayerInteractEvent.RightClickItem e){
        if(PocketService.inPocket(e.getLevel())&&!StorageItem.isTerminal(e.getItemStack())&&!e.getItemStack().is(Content.BACKPACK.get())&&!e.getItemStack().is(Content.GUIDE.get())){e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void entityUse(PlayerInteractEvent.EntityInteract e){if(PocketService.inPocket(e.getLevel()))e.setCanceled(true);}
    private static PocketContent.EntranceEntity lookingAt(ServerPlayer p){
        if(p.pick(6,0,false) instanceof BlockHitResult hit&&p.serverLevel().hasChunkAt(hit.getBlockPos())&&p.serverLevel().getBlockEntity(hit.getBlockPos()) instanceof PocketContent.EntranceEntity e)return e;
        throw new IllegalArgumentException("Look at your nearby Skygrid Cube");
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){
        e.getDispatcher().register(Commands.literal("anya").then(StorageSettingsCommands.pocketCommands()
            .executes(c->{var p=c.getSource().getPlayerOrException();AnyaStorage.tell(p,PocketService.status());try{for(var r:PocketService.ledger(p.getServer()).pockets())if(r.owner().equals(p.getUUID()))AnyaStorage.tell(p,r.id()+" | "+r.stage()+" | "+r.theme());}catch(java.io.IOException ex){AnyaStorage.tell(p,ex.getMessage());}return 1;})
            .then(Commands.literal("exit").executes(c->{var p=c.getSource().getPlayerOrException();try{if(PocketService.inPocket(p.level())||PocketService.ledger(p.getServer()).returning(p.getUUID())!=null)PocketService.recover(p);else AnyaStorage.tell(p,"No unfinished pocket visit to recover.");}catch(java.io.IOException ex){if(PocketService.inPocket(p.level()))PocketService.recover(p);else AnyaStorage.tell(p,ex.getMessage());}return 1;}))
            .then(Commands.literal("reanchor").then(Commands.argument("identity",StringArgumentType.word()).executes(c->{var p=c.getSource().getPlayerOrException();try{PocketService.reanchor(p,UUID.fromString(StringArgumentType.getString(c,"identity")),lookingAt(p),p.getMainHandItem());}catch(IllegalArgumentException ex){AnyaStorage.tell(p,ex.getMessage());}return 1;})))
            .then(Commands.literal("build").executes(c->{AnyaStorage.tell(c.getSource().getPlayerOrException(),RoomEditor.help()+" Room Plan is optional.");return 1;})
                .then(RoomPlans.commands())
                .then(Commands.literal("pads").executes(c->{ChestPadGuide.show(c.getSource().getPlayerOrException());return 1;}))
                .then(Commands.literal("place").executes(c->{RoomEditor.command(c.getSource().getPlayerOrException(),false);return 1;}))
                .then(Commands.literal("remove").executes(c->{RoomEditor.command(c.getSource().getPlayerOrException(),true);return 1;})))
            .then(Commands.literal("theme").then(Commands.argument("name",StringArgumentType.word()).executes(c->{var p=c.getSource().getPlayerOrException();try{PocketService.theme(p,lookingAt(p).pocket,StringArgumentType.getString(c,"name"));}catch(IllegalArgumentException ex){AnyaStorage.tell(p,ex.getMessage());}return 1;})))
        ));
    }
    private PocketEvents(){}
}
