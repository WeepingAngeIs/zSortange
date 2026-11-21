package dev.anya.feedingreview;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Read-only assertions on the optional provider's real per-player records and derived diversity. */
final class OnionHistory {
    record Snapshot(List<String> newestFirst,double diversity,float maxHealth){}
    private final Method capability;
    OnionHistory(boolean installed)throws Exception{
        capability=installed?Class.forName("team.creative.solonion.api.SOLOnionAPI").getMethod("getFoodCapability",Player.class):null;
    }
    Snapshot read(ServerPlayer p)throws Exception{
        if(capability==null)return new Snapshot(List.of(),0,p.getMaxHealth());
        Object record=capability.invoke(null,p);
        List<String> items=new ArrayList<>();
        for(Object value:(Iterable<?>)record)items.add(BuiltInRegistries.ITEM.getKey(((ItemStack)value).getItem()).toString());
        int total=((Number)record.getClass().getMethod("trackCount").invoke(record)).intValue();
        FeedingReview.check(total==items.size(),"Provider count disagrees with its recorded meal list");
        double diversity=((Number)record.getClass().getMethod("foodDiversity",LivingEntity.class).invoke(record,p)).doubleValue();
        return new Snapshot(List.copyOf(items),diversity,p.getMaxHealth());
    }
}
