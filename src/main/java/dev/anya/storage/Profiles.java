package dev.anya.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

public final class Profiles extends SavedData {
    public static final class PlayerState {
        public long protectedSlots=511L;
        final FieldKit.Preferences fieldKit=new FieldKit.Preferences();
        public UUID wornBag;
        public boolean guideGranted;
        public final Set<String> favorites=new TreeSet<>();
        public String lastReport="No transfer attempted";
        public long lastAction=-100000;
        public boolean protectedSlot(int slot) { return slot<0||slot>=36||(protectedSlots&(1L<<slot))!=0; }
    }
    private final Map<UUID,PlayerState> players=new HashMap<>();
    public final Set<UUID> rewardedDragons=new HashSet<>();
    public static Profiles get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Profiles::new,Profiles::load,null),"anya_storage_profiles"); }
    public static PlayerState of(ServerPlayer player) { return get(player.getServer()).players.computeIfAbsent(player.getUUID(),id->new PlayerState()); }
    public static void changed(ServerPlayer p) { get(p.getServer()).setDirty(); }
    public static boolean cooldown(ServerPlayer p) {
        PlayerState state=of(p); long now=p.serverLevel().getGameTime();
        if(now-state.lastAction<8 && now>=state.lastAction) return false;
        state.lastAction=now; return true;
    }
    public static Profiles load(CompoundTag tag,HolderLookup.Provider lookup) {
        Profiles data=new Profiles();
        ListTag list=tag.getList("Players",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++) {
            CompoundTag entry=list.getCompound(i); if(!entry.hasUUID("Id")) continue;
            PlayerState p=new PlayerState(); p.guideGranted=entry.getBoolean("GuideGranted"); if(entry.hasUUID("WornBag"))p.wornBag=entry.getUUID("WornBag"); p.protectedSlots=entry.getLong("Protected")&((1L<<36)-1);
            ListTag favorites=entry.getList("Favorites",Tag.TAG_STRING);
            for(int j=0;j<Math.min(256,favorites.size());j++) p.favorites.add(favorites.getString(j));
            p.fieldKit.read(entry,lookup);
            data.players.put(entry.getUUID("Id"),p);
        }
        ListTag dragons=tag.getList("Dragons",Tag.TAG_INT_ARRAY);
        for(Tag entry:dragons) try { data.rewardedDragons.add(NbtUtils.loadUUID(entry)); } catch(IllegalArgumentException ignored) { }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup) {
        ListTag list=new ListTag();
        players.forEach((uuid,p)->{CompoundTag entry=new CompoundTag();entry.putUUID("Id",uuid);entry.putBoolean("GuideGranted",p.guideGranted);if(p.wornBag!=null)entry.putUUID("WornBag",p.wornBag);entry.putLong("Protected",p.protectedSlots);ListTag favorites=new ListTag();p.favorites.forEach(s->favorites.add(StringTag.valueOf(s)));entry.put("Favorites",favorites);p.fieldKit.write(entry,lookup);list.add(entry);});
        tag.put("Players",list); ListTag dragons=new ListTag();rewardedDragons.forEach(uuid->dragons.add(NbtUtils.createUUID(uuid)));tag.put("Dragons",dragons);return tag;
    }
}
