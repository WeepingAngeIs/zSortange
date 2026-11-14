package dev.anya.storage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import java.util.List;
public final class BackpackItem extends Item {
    public BackpackItem(Properties p){super(p);}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(player instanceof ServerPlayer p){
            try { StorageData.get(p.getServer()).bag(stack);p.getInventory().setChanged();StorageMenu.openBag(p,hand==InteractionHand.MAIN_HAND?p.getInventory().selected:40); }
            catch(IllegalArgumentException ex){AnyaStorage.tell(p,ex.getMessage());}
        }
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        Rules.Tier tier=Rules.current.tier(StorageData.itemTier(stack));
        lines.add(Component.literal(tier==null?"Retired tier: contents retained":tier.name+" | "+tier.slots+" slots | "+tier.upgrades+" upgrades"));
        lines.add(Component.literal("Right-click to open. Contents belong to this world."));
        lines.add(Component.literal("Creative copies share one inventory identity."));
    }
}
