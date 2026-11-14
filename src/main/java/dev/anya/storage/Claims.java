package dev.anya.storage;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;
import java.util.List;
/** Optional public OPAC API adapter. Reflection failure denies access, never bypasses protection. */
public final class Claims {
    private static Object protection;
    private static Method check;
    private static String failure;
    private static boolean initialized;
    private Claims(){}
    public static void reset(){protection=null;check=null;failure=null;initialized=false;}
    private static void initialize(MinecraftServer server){
        if(initialized)return;initialized=true;
        for(String id:List.of("ftbchunks","flan","griefdefender","goml","landclaim"))if(ModList.get().isLoaded(id)){failure="Unsupported claims adapter: "+id;return;}
        if(!ModList.get().isLoaded("openpartiesandclaims"))return;
        try {
            Class<?> apiClass=Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Object api=apiClass.getMethod("get",MinecraftServer.class).invoke(null,server);
            protection=apiClass.getMethod("getChunkProtection").invoke(api);
            Class<?> iface=Class.forName("xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI");
            try{check=iface.getMethod("onBlockInteraction",Entity.class,InteractionHand.class,ItemStack.class,ServerLevel.class,BlockPos.class,Direction.class,boolean.class,boolean.class,boolean.class);}
            catch(NoSuchMethodException old){check=iface.getMethod("onBlockInteraction",Entity.class,InteractionHand.class,ItemStack.class,ServerLevel.class,BlockPos.class,Direction.class,boolean.class,boolean.class);}
        } catch(Exception e){failure="OPAC API unavailable; transfers blocked";AnyaStorage.LOG.error(failure,e);}
    }
    public static boolean allowed(ServerPlayer player,BlockPos pos){
        if(player.isSpectator()||!player.isAlive()||!player.serverLevel().hasChunkAt(pos))return false;
        initialize(player.getServer());if(failure!=null)return false;if(protection==null)return true;
        try {
            Object result=check.getParameterCount()==9?check.invoke(protection,player,InteractionHand.MAIN_HAND,ItemStack.EMPTY,player.serverLevel(),pos,Direction.UP,false,false,true):check.invoke(protection,player,InteractionHand.MAIN_HAND,ItemStack.EMPTY,player.serverLevel(),pos,Direction.UP,false,false);
            return Boolean.FALSE.equals(result);
        }catch(Exception e){failure="OPAC permission check failed; transfers blocked";AnyaStorage.LOG.error(failure,e);return false;}
    }
    public static String status(MinecraftServer server){initialize(server);return failure!=null?failure:protection!=null?"OPAC API active (installed-version gameplay test still required)":"No recognized claims mod installed";}
}
