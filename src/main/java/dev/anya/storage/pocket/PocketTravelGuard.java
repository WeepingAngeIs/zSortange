package dev.anya.storage.pocket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;

/** Optional carrying check. Does not move, clear, serialize or copy another mod's held contents. */
public final class PocketTravelGuard {
    public static String reason(ServerPlayer player){
        if(player.isPassenger()||player.isVehicle())return "Dismount and leave passengers outside before entering";
        if(!ModList.get().isLoaded("carryon"))return "";
        try{
            Class<?> manager=Class.forName("tschipp.carryon.common.carry.CarryOnDataManager");
            Class<?> data=Class.forName("tschipp.carryon.common.carry.CarryOnData");
            Method get=manager.getMethod("getCarryData",Player.class);
            Object held=get.invoke(null,player);
            if(held==null||!(data.getMethod("isCarrying").invoke(held) instanceof Boolean carrying))return "Carry On state unavailable; pocket entry refused";
            return carrying?"Put down the Carry On object before entering pocket storage":"";
        }catch(ReflectiveOperationException|LinkageError|RuntimeException error){return "Carry On API unavailable; pocket entry refused without changing held contents";}
    }
    private PocketTravelGuard(){}
}
