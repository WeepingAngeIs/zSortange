package dev.anya.storage;

import dev.anya.storage.pocket.PocketService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class StorageItem extends Item {
    public final String kind;
    public StorageItem(String kind,Properties properties){super(properties);this.kind=kind;}
    public static boolean isTablet(ItemStack stack) {
        return stack.is(Content.CUPID_TABLET.get()) || isCreativeTablet(stack);
    }
    public static boolean isCreativeTablet(ItemStack stack) { return stack.is(Content.CREATIVE_TABLET.get()); }
    public static boolean isTerminal(ItemStack stack) { return isTablet(stack) || stack.is(Content.REMOTE.get()); }
    public static boolean permittedTablet(ServerPlayer player,ItemStack stack) {
        return isCreativeTablet(stack) ? CreativeTabletAccess.mayUse(player,stack) : isTerminal(stack);
    }
    public static ItemStack tabletInHand(ServerPlayer player,InteractionHand hand) {
        ItemStack current=player.getItemInHand(hand);
        if (!current.is(Content.REMOTE.get())) return current;
        ItemStack tablet=current.transmuteCopy(Content.CUPID_TABLET.get());
        player.setItemInHand(hand,tablet);
        player.getInventory().setChanged();
        return tablet;
    }
    @Override public void inventoryTick(ItemStack stack,Level level,Entity entity,int slot,boolean selected) {
        if (!stack.is(Content.REMOTE.get()) || !(entity instanceof ServerPlayer player)) return;
        var inventory=player.getInventory();
        if (slot<0 || slot>=inventory.getContainerSize() || inventory.getItem(slot)!=stack) {
            // Some tick callers pass a compartment-relative index, notably zero for the offhand.
            for(slot=0;slot<inventory.getContainerSize();slot++)if(inventory.getItem(slot)==stack)break;
            if(slot==inventory.getContainerSize())return;
        }
        inventory.setItem(slot,stack.transmuteCopy(Content.CUPID_TABLET.get()));
        inventory.setChanged();
    }
    @Override public boolean canFitInsideContainerItems(){return !kind.equals("backpack");}
    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context){
        if(kind.equals("backpack") && context.getPlayer()!=null && context.getPlayer().isShiftKeyDown())
            return PlacedBackpackBlock.place(context);
        return InteractionResult.PASS;
    }
    @Override public Component getName(ItemStack stack){if(kind.equals("backpack"))return Component.literal(dev.anya.storage.appearance.Looks.backpackName(stack));return super.getName(stack);}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(!level.isClientSide&&player instanceof ServerPlayer p){
            if(kind.equals("backpack")){int slot=hand==InteractionHand.MAIN_HAND?p.getInventory().selected:40;StoreMenu.bag(p,new BagInventory(p,slot),player.isShiftKeyDown());}
            else {
                stack=tabletInHand(p,hand);
                if (!permittedTablet(p,stack)) {
                    p.displayClientMessage(Component.literal("You cannot use this tablet right now."),true);
                    return InteractionResultHolder.fail(stack);
                }
                if (PocketService.openVisitedTablet(p,hand)) return InteractionResultHolder.consume(stack);
                if (isCreativeTablet(stack)) {
                    CreativeTabletAccess.open(p,hand);
                    return InteractionResultHolder.consume(stack);
                }
                StorageEntity hub=bound(p,stack);
                if(hub!=null){
                    if(!PocketService.remote(p,stack,hub)) StoreMenu.remote(p,hub,true);
                }else if(BagInventory.data(stack).contains("HubPos")){
                    AnyaStorage.tell(p,"Bound hub unavailable. Check its dimension, range and your access.");
                }else AnyaStorage.tell(p,"Sneak-right-click a permitted hub to bind this item. Use it within that hub's radius.");
            }
        }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        var player = context.getPlayer();
        if(!isTerminal(context.getItemInHand())||player==null||!player.isShiftKeyDown())return InteractionResult.PASS;
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        if(!(player instanceof ServerPlayer p))return InteractionResult.PASS;
        ItemStack tablet=tabletInHand(p,context.getHand());
        if (!permittedTablet(p,tablet)) {
            p.displayClientMessage(Component.literal("You cannot use this tablet right now."),true);
            return InteractionResult.FAIL;
        }
        var be=context.getLevel().getBlockEntity(context.getClickedPos());
        if(be instanceof dev.anya.storage.pocket.PocketContent.EntranceEntity entrance){PocketService.link(p,entrance,tablet);return InteractionResult.CONSUME;}
        if(be instanceof StorageEntity hub&&hub.kind().equals("hub")){
            boolean creative=isCreativeTablet(tablet);
            String reason=creative ? Network.hubReason(p,hub) : Safety.reason(p,hub,null);
            if(!reason.isEmpty()){AnyaStorage.tell(p,reason);return InteractionResult.FAIL;}
            if(creative && !p.canInteractWithBlock(hub.getBlockPos(),1))return InteractionResult.FAIL;
            CompoundTag tag=BagInventory.data(tablet);
            String dimension=p.level().dimension().location().toString();
            long pos=hub.getBlockPos().asLong();
            boolean same=!tag.hasUUID("PocketId") && tag.contains("HubPos") && tag.getLong("HubPos")==pos && dimension.equals(tag.getString("HubDimension"));
            if (!same) {
                tag.putString("HubDimension",dimension);tag.putLong("HubPos",pos);tag.remove("PocketId");BagInventory.data(tablet,tag);
            }
            if (creative) CreativeTabletAccess.bind(p,tablet,hub);
            if (same) return InteractionResult.CONSUME;
            AnyaStorage.tell(p,creative ? "UwU Tablet bound to "+hub.baseName+"."
                    : "Bound to "+hub.baseName+". Hub radius is "+hub.radius()+" blocks.");
            return InteractionResult.CONSUME;
        }
        StorageEntity hub=bound(p,tablet);
        if(hub!=null&&hub.ownedBy(p)&&Network.supported(be)&&Network.within(hub.getBlockPos(),context.getClickedPos(),hub.radius())&&Safety.reason(p,be,hub).isEmpty()){
            StoreMenu.route(p,hub,context.getClickedPos());return InteractionResult.CONSUME;
        }
        AnyaStorage.tell(p,"Bind to your hub first, then sneak-use on a supported chest inside its range to edit routing.");return InteractionResult.CONSUME;
    }
    public static StorageEntity bound(ServerPlayer p,ItemStack stack){
        if (!permittedTablet(p,stack)) return null;
        CompoundTag tag=BagInventory.data(stack);
        if(!tag.contains("HubPos")||!tag.getString("HubDimension").equals(p.level().dimension().location().toString()))return null;
        BlockPos pos=BlockPos.of(tag.getLong("HubPos"));if(!p.serverLevel().hasChunkAt(pos))return null;
        if(p.serverLevel().getBlockEntity(pos) instanceof StorageEntity hub&&hub.kind().equals("hub")&&Network.hubReason(p,hub).isEmpty())return hub;
        return null;
    }
    public static void openFirst(ServerPlayer p){
        if(!p.isAlive()||p.isSpectator()||p.containerMenu!=p.inventoryMenu||!Profiles.cooldown(p))return;
        for(var source:BackpackAccess.priority(p)){
            if(!BackpackAccess.unique(p,source.stack())){AnyaStorage.tell(p,"Duplicate backpack identity detected. Separate the duplicate before opening; contents were not changed.");return;}
            StoreMenu.bag(p,source.open(p),false);return;
        }
        AnyaStorage.tell(p,"No Anya backpack in your native back slot, main inventory or offhand.");
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        if (!kind.equals("backpack")) return;
        if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) lines.add(getName(stack));
        CompoundTag tag = BagInventory.data(stack);
        int capacity = tag.contains("Capacity") ? tag.getInt("Capacity") : 27;
        int upgrades = tag.contains("UpgradeSlots") ? tag.getInt("UpgradeSlots") : 1;
        lines.add(Component.translatable("tooltip.anyastorage.backpack.stats", capacity, upgrades));
        var look = dev.anya.storage.appearance.Looks.of(stack);
        lines.add(Component.translatable("tooltip.anyastorage.backpack.appearance", look.element(), look.skin()));
    }
}
