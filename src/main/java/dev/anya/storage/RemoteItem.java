package dev.anya.storage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
public final class RemoteItem extends Item {
    public RemoteItem(Properties p){super(p);}
    public static void bind(ServerPlayer p,ItemStack stack,StorageBlockEntity hub){
        if(!hub.allowed(p)||!Claims.allowed(p,hub.getBlockPos())){AnyaStorage.tell(p,"You cannot bind to this base.");return;}
        CompoundTag t=StorageData.metadata(stack);t.putString("AnyaDimension",p.level().dimension().location().toString());t.putLong("AnyaHub",hub.getBlockPos().asLong());t.putUUID("AnyaHubId",hub.identity);StorageData.metadata(stack,t);p.getInventory().setChanged();AnyaStorage.tell(p,"Button bound to "+hub.name+". Range: "+hub.radius()+" blocks.");
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(player instanceof ServerPlayer p){
            CompoundTag t=StorageData.metadata(stack);
            if(!Rules.current.network)AnyaStorage.tell(p,"Base networks are disabled. Physical containers remain accessible.");
            else if(!t.hasUUID("AnyaHubId"))AnyaStorage.tell(p,"Sneak-right-click a storage hub to bind this button.");
            else if(!t.getString("AnyaDimension").equals(p.level().dimension().location().toString()))AnyaStorage.tell(p,"The bound base is in another dimension.");
            else {
                BlockPos pos=BlockPos.of(t.getLong("AnyaHub"));
                if(!p.serverLevel().hasChunkAt(pos))AnyaStorage.tell(p,"The hub is unloaded. No chunk was force-loaded.");
                else if(p.serverLevel().getBlockEntity(pos) instanceof StorageBlockEntity b && b.kind()==0 && b.identity.equals(t.getUUID("AnyaHubId")) && b.inRange(p.blockPosition()) && b.allowed(p) && Claims.allowed(p,pos))StorageMenu.openBlock(p,b);
                else AnyaStorage.tell(p,"Hub unavailable: check range, ownership, claims, or whether it was replaced.");
            }
        }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
}
