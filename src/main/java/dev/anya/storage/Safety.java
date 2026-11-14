package dev.anya.storage;

import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import java.lang.reflect.*;
import java.util.*;

/** Permission checks use the actor, never the hub owner as a substitute identity. */
public final class Safety {
    private static Method apiGet,protectionGet,blockCheck,pickupCheck;
    private static boolean initialized,failed;
    private static String message="No claim adapter initialized";
    public static void clear() { initialized=false;failed=false;apiGet=null; }
    public static String status() { init();return message; }
    private static void init() {
        if(initialized) return; initialized=true;
        for(String mod:List.of("ftbchunks","flan","claimchunk","griefdefender")) if(ModList.get().isLoaded(mod)) { failed=true;message="Remote access blocked: unsupported protection mod "+mod;return; }
        if(!ModList.get().isLoaded("openpartiesandclaims")) { message="OPAC absent; vanilla locks, spawn protection and storage ownership enforced";return; }
        try {
            Class<?> api=Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Class<?> protection=Class.forName("xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI");
            apiGet=api.getMethod("get",MinecraftServer.class); protectionGet=api.getMethod("getChunkProtection");
            blockCheck=protection.getMethod("onBlockInteraction",Entity.class,InteractionHand.class,ItemStack.class,ServerLevel.class,BlockPos.class,Direction.class,boolean.class,boolean.class,boolean.class);
            pickupCheck=protection.getMethod("onItemPickup",Entity.class,ItemEntity.class);
            message="OPAC API detected; each transfer checks the acting player's access";
        } catch(ReflectiveOperationException e) { failed=true;message="OPAC API mismatch: remote storage blocked";AnyaStorage.LOG.error(message,e); }
    }
    public static String reason(ServerPlayer p,BlockEntity be,StorageEntity hub) {
        if(p.isSpectator()||!p.isAlive()) return "Player cannot interact";
        if(be==null||be.isRemoved()||be.getLevel()!=p.level()) return "Container missing or wrong dimension";
        ServerLevel level=p.serverLevel(); BlockPos pos=be.getBlockPos();
        if(!level.hasChunkAt(pos)) return "Chunk unloaded";
        if(!level.getWorldBorder().isWithinBounds(pos)) return "Outside world border";
        if(p.getServer().isUnderSpawnProtection(level,pos,p)) return "Spawn protection";
        if(be instanceof StorageEntity storage && !storage.allowed(p) && !(hub!=null && hub.allowed(p) && Objects.equals(storage.owner,hub.owner))) return "Storage owner has not trusted you";
        if(be instanceof BaseContainerBlockEntity vanilla && !vanilla.canOpen(p)) return "Container lock";
        if(be instanceof RandomizableContainerBlockEntity loot && loot.getLootTable()!=null) return "Unopened loot container";
        init(); if(failed) return message;
        if(apiGet!=null) try {
            Object protection=protectionGet.invoke(apiGet.invoke(null,p.getServer()));
            if((Boolean)blockCheck.invoke(protection,p,InteractionHand.MAIN_HAND,ItemStack.EMPTY,level,pos,Direction.UP,false,false,true)) return "Claim permission denied";
        } catch(ReflectiveOperationException|RuntimeException e) { failed=true;message="Claim API failed: remote storage blocked";AnyaStorage.LOG.error(message,e);return message; }
        StorageAccessEvent event=new StorageAccessEvent(p,pos);NeoForge.EVENT_BUS.post(event);
        return event.isCanceled()?"Storage access cancelled by an addon":"";
    }
    public static boolean pickup(ServerPlayer p,ItemEntity item) {
        init(); if(failed) return false;
        if(apiGet==null) return true;
        try { return !(Boolean)pickupCheck.invoke(protectionGet.invoke(apiGet.invoke(null,p.getServer())),p,item); }
        catch(ReflectiveOperationException e) { failed=true;message="Claim pickup check failed; remote actions blocked";return false; }
    }
}
