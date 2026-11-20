package dev.anya.storage.guide;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import java.util.List;

public final class GuideItem extends Item {
    public GuideItem(Properties p){super(p);}
    @Override public Component getName(ItemStack s){return Component.literal("Ancient Technology");}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        if(player instanceof ServerPlayer p)GuideService.open(p);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack s,TooltipContext c,List<Component> lines,TooltipFlag f){
        lines.add(Component.literal("An illustrated field guide to Anya Storage"));
        lines.add(Component.literal("Right-click: open | Recipes follow this server's rules"));
        lines.add(Component.literal("Lost copy: /anya guide recover (one ordinary Book)"));
    }
}
