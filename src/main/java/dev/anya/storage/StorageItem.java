package dev.anya.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class StorageItem extends Item {
    public final String kind;
    public StorageItem(String kind,Properties properties){super(properties);this.kind=kind;}
    @Override public boolean canFitInsideContainerItems(){return !kind.equals("backpack");}
    @Override public Component getName(ItemStack stack){if(kind.equals("backpack")){String tier=BagInventory.data(stack).getString("TierName");return Component.literal((tier.isBlank()?"Starter":tier)+" Backpack");}return super.getName(stack);}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(!level.isClientSide&&player instanceof ServerPlayer p){
            if(kind.equals("backpack")){int slot=hand==InteractionHand.MAIN_HAND?p.getInventory().selected:40;StoreMenu.bag(p,new BagInventory(p,slot),player.isShiftKeyDown());}
            else {StorageEntity hub=bound(p,stack);if(hub!=null){if(!dev.anya.storage.pocket.PocketService.remote(p,stack,hub))StoreMenu.network(p,hub);}else AnyaStorage.tell(p,"Sneak-right-click a permitted hub to bind this remote. Use it within that hub's radius.");}
        }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(!kind.equals("remote")||!context.getPlayer().isShiftKeyDown())return InteractionResult.PASS;
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        if(!(context.getPlayer() instanceof ServerPlayer p))return InteractionResult.PASS;
        var be=context.getLevel().getBlockEntity(context.getClickedPos());
        if(be instanceof dev.anya.storage.pocket.PocketContent.EntranceEntity entrance){dev.anya.storage.pocket.PocketService.link(p,entrance,context.getItemInHand());return InteractionResult.CONSUME;}
        if(be instanceof StorageEntity hub&&hub.kind().equals("hub")){
            String reason=Safety.reason(p,hub,null);if(!reason.isEmpty()){AnyaStorage.tell(p,reason);return InteractionResult.FAIL;}
            CompoundTag tag=BagInventory.data(context.getItemInHand());tag.putString("HubDimension",p.level().dimension().location().toString());tag.putLong("HubPos",hub.getBlockPos().asLong());tag.remove("PocketId");BagInventory.data(context.getItemInHand(),tag);
            AnyaStorage.tell(p,"Remote bound to "+hub.baseName+"; radius "+hub.radius()+" blocks.");return InteractionResult.CONSUME;
        }
        StorageEntity hub=bound(p,context.getItemInHand());
        if(hub!=null&&hub.ownedBy(p)&&Network.supported(be)&&Network.within(hub.getBlockPos(),context.getClickedPos(),hub.radius())&&Safety.reason(p,be,hub).isEmpty()){
            StoreMenu.route(p,hub,context.getClickedPos());return InteractionResult.CONSUME;
        }
        AnyaStorage.tell(p,"Bind to your hub first, then sneak-use on a supported chest inside its range to edit routing.");return InteractionResult.CONSUME;
    }
    public static StorageEntity bound(ServerPlayer p,ItemStack stack){
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
        if(kind.equals("backpack")){CompoundTag tag=BagInventory.data(stack);lines.add(Component.literal("Slots: "+(tag.contains("Capacity")?tag.getInt("Capacity"):27)+" | Upgrades: "+(tag.contains("UpgradeSlots")?tag.getInt("UpgradeSlots"):1)));lines.add(Component.literal("Right-click: open | Sneak-use: upgrades"));lines.add(Component.literal("Backpacks and filled portable containers cannot nest."));var look=dev.anya.storage.appearance.Looks.of(stack);lines.add(Component.literal("Element: "+look.element()+" | Skin: "+look.skin()));lines.add(Component.literal("Accessories back slot supported when installed. Wardrobe remains available."));}
        else {lines.add(Component.literal("Sneak-use on hub: bind | Use: open base storage"));lines.add(Component.literal("Sneak-use on a chest: routing (owner only)"));}
    }
}
