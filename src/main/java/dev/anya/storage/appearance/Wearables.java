package dev.anya.storage.appearance;

import dev.anya.storage.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** A worn backpack is a reference to ONE real inventory item, never a second inventory or armor slot. */
public final class Wearables {
    public static final ResourceLocation ARMOR=ExpansionPackets.id("worn_armor"),KNOCKBACK=ExpansionPackets.id("worn_knockback"),SWIM=ExpansionPackets.id("worn_swim");
    private static final Map<UUID,String> sent=new HashMap<>();
    private static final String WILD="AnyaWildBackpack";
    public static ItemStack selected(ServerPlayer p){
        UUID wanted=Profiles.of(p).wornBag;if(wanted==null||!p.isAlive()||p.isSpectator())return ItemStack.EMPTY;
        ItemStack found=ItemStack.EMPTY;
        for(int i=0;i<=40;i++){
            if(i>=36&&i<40)continue;ItemStack s=p.getInventory().getItem(i);if(!s.is(Content.BACKPACK.get())||s.getCount()!=1)continue;
            CompoundTag d=BagInventory.data(s);if(d.hasUUID("BagId")&&wanted.equals(d.getUUID("BagId"))){if(!found.isEmpty())return ItemStack.EMPTY;found=s;}
        }
        return found;
    }
    public static void toggle(ServerPlayer p,ItemStack bag){
        BagInventory.initialize(bag);UUID id=BagInventory.data(bag).getUUID("BagId");var state=Profiles.of(p);state.wornBag=id.equals(state.wornBag)?null:id;Profiles.changed(p);refresh(p);
    }
    public static boolean highTier(ItemStack bag){
        String id=BagInventory.data(bag).getString("Tier");Set<String> seen=new HashSet<>();
        while(!id.isBlank()&&seen.add(id)&&seen.size()<=64){if(id.equals("netherite"))return true;Config.Tier t=Config.get().tier(id);if(t==null)return false;id=t.predecessor;}return false;
    }
    public static void tick(PlayerTickEvent.Pre event){if(event.getEntity() instanceof ServerPlayer p)refresh(p);}
    public static void refresh(ServerPlayer p){
        ItemStack bag=selected(p);var rules=ExpansionRules.get();String e=Looks.of(bag).element();boolean active=rules.elementalEffects&&Config.get().backpacks&&!bag.isEmpty();
        set(p,Attributes.ARMOR,ARMOR,active?(e.equals("stone")?rules.stoneArmor:e.equals("diamond")?rules.diamondArmor:0):0,AttributeModifier.Operation.ADD_VALUE);
        set(p,Attributes.KNOCKBACK_RESISTANCE,KNOCKBACK,active&&highTier(bag)?rules.netheriteKnockback:0,AttributeModifier.Operation.ADD_VALUE);
        set(p,NeoForgeMod.SWIM_SPEED,SWIM,active&&e.equals("water")&&p.isInWater()?rules.waterSwimBonus:0,AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        var look=publicLook(p);String signature=look.texture()+"|"+look.element();
        if(!Objects.equals(sent.put(p.getUUID(),signature),signature)&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p)PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,look);
    }
    public static void strip(ServerPlayer p){
        set(p,Attributes.ARMOR,ARMOR,0,AttributeModifier.Operation.ADD_VALUE);set(p,Attributes.KNOCKBACK_RESISTANCE,KNOCKBACK,0,AttributeModifier.Operation.ADD_VALUE);set(p,NeoForgeMod.SWIM_SPEED,SWIM,0,AttributeModifier.Operation.ADD_MULTIPLIED_BASE);sent.remove(p.getUUID());
    }
    private static void set(ServerPlayer p,Holder<Attribute> type,ResourceLocation id,double amount,AttributeModifier.Operation op){
        AttributeInstance attribute=p.getAttribute(type);if(attribute==null)return;AttributeModifier old=attribute.getModifier(id);
        if(old!=null&&old.amount()==amount&&old.operation()==op)return;
        if(old!=null)attribute.removeModifier(id);if(amount!=0)attribute.addTransientModifier(new AttributeModifier(id,amount,op));
    }
    public static void incoming(LivingIncomingDamageEvent event){
        if(!(event.getEntity() instanceof ServerPlayer p))return;refresh(p);
        if(ExpansionRules.get().elementalEffects&&Config.get().backpacks&&Looks.of(selected(p)).element().equals("magma")&&!selected(p).isEmpty()&&event.getSource().is(DamageTypeTags.IS_FIRE))
            event.setAmount((float)(event.getAmount()*(1-ExpansionRules.get().magmaReduction)));
    }
    public static ExpansionPackets.Look publicLook(LivingEntity entity){
        ItemStack bag=entity instanceof ServerPlayer p?selected(p):ItemStack.EMPTY;String texture="",element="plain";
        if(entity instanceof ServerPlayer&&ExpansionRules.get().wearableModels&&!entity.isInvisible()&&!bag.isEmpty()){var l=Looks.of(bag);texture=l.texture();element=ExpansionRules.get().cosmeticParticles?l.element():"disabled";}
        else if(entity instanceof Monster&&ExpansionRules.get().monsterBackpacks&&entity.getPersistentData().contains(WILD)&&!entity.isInvisible()){texture=entity.getPersistentData().getCompound(WILD).getString("Skin");if(!ExpansionRules.get().cosmeticParticles)element="disabled";}
        return new ExpansionPackets.Look(entity.getId(),entity.getUUID(),-1,0,texture,element,"atelier",ExpansionPackets.NONE,false);
    }
    public static void track(PlayerEvent.StartTracking event){if(event.getEntity() instanceof ServerPlayer p&&event.getTarget() instanceof LivingEntity living)PacketDistributor.sendToPlayer(p,publicLook(living));}
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p){strip(p);dev.anya.storage.images.InteriorImages.disconnect(p);}}
    public static void clone(PlayerEvent.Clone event){if(event.getOriginal() instanceof ServerPlayer old)strip(old);if(event.getEntity() instanceof ServerPlayer p)strip(p);}
    public static void spawn(FinalizeSpawnEvent event){
        var rules=ExpansionRules.get();Mob m=event.getEntity();
        if(!rules.monsterBackpacks||!(m instanceof Monster)||!(event.getSpawnType()==MobSpawnType.NATURAL||event.getSpawnType()==MobSpawnType.CHUNK_GENERATION))return;
        if(!rules.monsterSpecies.contains(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString())||m.hasCustomName()||!m.getPersistentData().isEmpty())return;
        for(ItemStack gear:m.getArmorSlots())if(!gear.isEmpty())return;
        if(m.getRandom().nextDouble()>=rules.monsterChance)return;
        CompoundTag tag=new CompoundTag();tag.putString("Skin",m.getRandom().nextBoolean()?"plain":"leaf_oak");m.getPersistentData().put(WILD,tag);
        // A decorative marker, not EquipmentSlot.CHEST: never overwrites Apotheosis or other equipment.
    }
    public static void death(LivingDeathEvent event){
        if(event.getEntity() instanceof ServerPlayer p){strip(p);return;}
        LivingEntity entity=event.getEntity();if(!entity.getPersistentData().contains(WILD))return;
        String skin=entity.getPersistentData().getCompound(WILD).getString("Skin");entity.getPersistentData().remove(WILD);
        if(!ExpansionRules.get().monsterBackpacks||!(event.getSource().getEntity() instanceof ServerPlayer p)||p.isCreative()||!p.level().getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)||entity.getRandom().nextDouble()>=ExpansionRules.get().monsterDropChance)return;
        ItemStack reward=new ItemStack(Content.BACKPACK.get());BagInventory.initialize(reward);if(skin.equals("leaf_oak"))Looks.apply(reward,"leaf",true);
        entity.spawnAtLocation(reward); // Fresh, empty, independently identified bag. No entity or player inventory is copied.
    }
    public static void clear(){sent.clear();}
    private Wearables(){}
}
