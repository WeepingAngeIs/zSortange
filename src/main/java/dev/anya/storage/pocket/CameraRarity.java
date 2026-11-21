package dev.anya.storage.pocket;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/** Optional Cobblemon 1.8 world-pool lookup. Mixed or unclassified species get ordinary priority. */
@EventBusSubscriber(modid="anyastorage")
public final class CameraRarity {
    private static final Map<MinecraftServer,Set<String>> byServer=new WeakHashMap<>();
    @SubscribeEvent public static void reload(OnDatapackSyncEvent event){
        if(event.getPlayer()==null)byServer.clear();
    }
    static boolean rare(MinecraftServer server,Object species)throws ReflectiveOperationException{
        return byServer.computeIfAbsent(server,ignored->load()).contains(species.getClass().getMethod("getResourceIdentifier").invoke(species).toString());
    }
    private static Set<String> load(){
        try{
            var pools=Class.forName("com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools");
            var pool=pools.getMethod("getWORLD_SPAWN_POOL").invoke(pools.getField("INSTANCE").get(null));
            var details=(Collection<?>)pool.getClass().getMethod("getDetails").invoke(pool);
            if(details.size()>16000)return Set.of();
            var type=Class.forName("com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail");
            var pokemon=type.getMethod("getPokemon");var species=pokemon.getReturnType().getMethod("getSpecies");var bucket=type.getMethod("getBucket");
            Set<String> rare=new HashSet<>(),ordinary=new HashSet<>();
            for(var detail:details){
                if(!type.isInstance(detail))continue;
                Object name=species.invoke(pokemon.invoke(detail));if(!(name instanceof String id) || id.isBlank())continue;
                String key=id.contains(":")?id:"cobblemon:"+id;
                if(ResourceLocation.tryParse(key)==null)continue;
                var tier=bucket.invoke(detail);
                if("rare".equals(tier)||"ultra-rare".equals(tier))rare.add(key);else ordinary.add(key);
            }
            rare.removeAll(ordinary);return Set.copyOf(rare);
        }catch(ReflectiveOperationException | RuntimeException unavailable){return Set.of();}
    }
}
