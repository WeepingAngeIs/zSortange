package dev.anya.storage;

import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import java.lang.reflect.*;
import java.util.*;

/** Permission checks use the actor, never the hub owner as a substitute identity. */
public final class Safety {
    private static Method apiGet,protectionGet,blockCheck,pickupCheck;
    private static boolean initialized,failed;
    private static String message="No claim adapter initialized";
    public static void clear(){initialized=false;failed=false;apiGet=null;protectionGet=null;blockCheck=null;pickupCheck=null;}
    public static String status(){init();return message;}
    private static void init(){
        if(initialized)return;initialized=true;
        for(String mod:List.of("ftbchunks","flan","claimchunk","griefdefender"))if(ModList.get().isLoaded(mod)){failed=true;message="Remote access blocked: unsupported protection mod "+mod;return;}
        if(!ModList.get().isLoaded("openpartiesandclaims")){message="OPAC absent; vanilla locks, spawn protection and storage ownership enforced";return;}
        try{
            Class<?> api=Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Class<?> protection=Class.forName("xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI");
            apiGet=api.getMethod("get",MinecraftServer.class);protectionGet=api.getMethod("getChunkProtection");
            blockCheck=protection.getMethod("onBlockInteraction",Entity.class,InteractionHand.class,ItemStack.class,ServerLevel.class,BlockPos.class,Direction.class,boolean.class,boolean.class,boolean.class);
            pickupCheck=protection.getMethod("onItemPickup",Entity.class,ItemEntity.class);
            message="OPAC API detected; each transfer checks the acting player's access";
        }catch(ReflectiveOperationException e){failed=true;message="OPAC API mismatch: remote storage blocked";AnyaStorage.LOG.error(message,e);}
    }
    public static String reason(ServerPlayer player,BlockEntity entity,StorageEntity hub){return reason(player,entity,hub,true);}
    private static String reason(ServerPlayer p,BlockEntity be,StorageEntity hub,boolean checkPartner){
        if(p.isSpectator()||!p.isAlive())return "Player cannot interact";
        if(be==null||be.isRemoved()||be.getLevel()!=p.level())return "Container missing or wrong dimension";
        ServerLevel level=p.serverLevel();BlockPos pos=be.getBlockPos();
        if(!level.hasChunkAt(pos))return "Chunk unloaded";
        if(!level.getWorldBorder().isWithinBounds(pos))return "Outside world border";
        if(p.getServer().isUnderSpawnProtection(level,pos,p))return "Spawn protection";
        if(be instanceof StorageEntity storage&&!storage.allowed(p)&&!(hub!=null&&hub.allowed(p)&&Objects.equals(storage.owner,hub.owner)))return "Storage owner has not trusted you";
        if(be instanceof BaseContainerBlockEntity vanilla&&!vanilla.canOpen(p))return "Container lock";
        if(be instanceof RandomizableContainerBlockEntity loot&&loot.getLootTable()!=null)return "Unopened loot container";
        if(checkPartner&&be instanceof ChestBlockEntity&&be.getBlockState().hasProperty(ChestBlock.TYPE)&&be.getBlockState().getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
            BlockPos partner=pos.relative(ChestBlock.getConnectedDirection(be.getBlockState()));
            if(!level.hasChunkAt(partner))return "Other half of double chest is unloaded";
            BlockEntity other=level.getBlockEntity(partner);
            if(!(other instanceof ChestBlockEntity))return "Other half of double chest is missing";
            String denial=reason(p,other,hub,false);if(!denial.isEmpty())return "Other half: "+denial;
        }
        return locationReason(p,level,pos);
    }
    /** Explicit target dimension for pocket operations; actor identity is never substituted. */
    public static String locationReason(ServerPlayer p,ServerLevel level,BlockPos pos){
        if(!p.isAlive()||p.isSpectator())return "Player cannot interact";
        if(level==null||!level.hasChunkAt(pos))return "Target chunk is not loaded";
        if(!level.getWorldBorder().isWithinBounds(pos))return "Outside world border";
        if(p.getServer().isUnderSpawnProtection(level,pos,p))return "Spawn protection";
        init();if(failed)return message;
        if(apiGet!=null)try{
            Object protection=protectionGet.invoke(apiGet.invoke(null,p.getServer()));
            if((Boolean)blockCheck.invoke(protection,p,InteractionHand.MAIN_HAND,ItemStack.EMPTY,level,pos,Direction.UP,false,false,true))return "Claim permission denied";
        }catch(ReflectiveOperationException|RuntimeException e){failed=true;message="Claim API failed: remote storage blocked";AnyaStorage.LOG.error(message,e);return message;}
        StorageAccessEvent event=new StorageAccessEvent(p,level,pos);NeoForge.EVENT_BUS.post(event);return event.isCanceled()?"Storage access cancelled by an addon":"";
    }
    /** Edit-specific OPAC checks: interaction permission is not permission to place or break. */
    public static String buildReason(ServerPlayer p,ServerLevel level,BlockPos pos,net.minecraft.world.level.block.state.BlockState state,boolean removing){
        String common=locationReason(p,level,pos);if(!common.isEmpty())return common;
        if(apiGet!=null)try{
            Object protection=protectionGet.invoke(apiGet.invoke(null,p.getServer()));
            boolean denied;
            if(removing)denied=(Boolean)blockCheck.invoke(protection,p,InteractionHand.MAIN_HAND,p.getMainHandItem(),level,pos,Direction.UP,true,false,true);
            else{
                Method place=Class.forName("xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI").getMethod("onEntityPlaceBlock",net.minecraft.world.level.block.state.BlockState.class,Entity.class,ServerLevel.class,BlockPos.class);
                denied=(Boolean)place.invoke(protection,state,p,level,pos);
            }
            if(denied)return "Claim construction permission denied";
        }catch(ReflectiveOperationException|RuntimeException e){return "Claim construction API unavailable; edit denied";}
        return "";
    }
    public static boolean pickup(ServerPlayer p,ItemEntity item){
        init();if(failed)return false;if(apiGet==null)return true;
        try{return !(Boolean)pickupCheck.invoke(protectionGet.invoke(apiGet.invoke(null,p.getServer())),p,item);}
        catch(ReflectiveOperationException e){failed=true;message="Claim pickup check failed; remote actions blocked";return false;}
    }
}
